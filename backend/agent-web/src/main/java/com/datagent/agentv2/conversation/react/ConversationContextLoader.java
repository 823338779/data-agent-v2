package com.datagent.agentv2.conversation.react;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.BoundedProcessPayload;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.ThinkingProcessItem;
import com.datagent.agentv2.conversation.domain.ThinkingProcessStatus;
import com.datagent.agentv2.conversation.domain.ToolThinkingProcessItem;
import com.datagent.agentv2.conversation.enums.MessageRole;
import com.datagent.agentv2.persistence.entity.ToolExecutionEntity;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ToolExecutionService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 从真实问答消息及其 ProcessItem 重建模型上下文，不读取前端展示投影。 */
@Service
@RequiredArgsConstructor
public class ConversationContextLoader {

    private static final String ERROR_EXECUTION_NOT_FOUND = "Agent execution was not found";
    private static final String ERROR_MISMATCHED_TOOL_PROCESS =
            "Tool process item does not match its execution row";
    private static final String KEY_PREVIEW = "preview";
    private static final String KEY_TRUNCATED = "truncated";
    private static final String KEY_ORIGINAL_SIZE_BYTES = "originalSizeBytes";
    private static final String KEY_SHA256 = "sha256";
    private static final String KEY_CONTENT_TYPE = "contentType";

    private final AgentExecutionService agentExecutions;
    private final ConversationMessageService messages;
    private final ToolExecutionService toolExecutions;
    private final ObjectMapper json;

    public List<ModelInputMessage> load(
            String executionId, long inputUpToConversationMessageSequence) {
        AgentExecution execution = agentExecutions.findByExecutionId(executionId);
        if (execution == null) {
            throw new IllegalStateException(ERROR_EXECUTION_NOT_FOUND);
        }
        List<ConversationMessage> rows =
                messages.findByConversationSessionIdUpTo(
                        execution.getConversationSessionId(), inputUpToConversationMessageSequence);
        Map<UUID, ToolExecutionEntity> executionsByProcessItem = executionIndex(executionId);
        List<ModelInputMessage> result = new ArrayList<>(rows.size());

        // 真实消息按会话顺序回放；assistant 的工具轮次在其正文之前展开为模型协议消息。
        for (ConversationMessage row : rows) {
            if (MessageRole.ASSISTANT.value().equals(row.getRole())) {
                appendAssistant(row, executionsByProcessItem, result);
            } else {
                result.add(ModelInputMessage.plain(row.getRole(), row.getContent()));
            }
        }
        return List.copyOf(result);
    }

    private Map<UUID, ToolExecutionEntity> executionIndex(String executionId) {
        Map<UUID, ToolExecutionEntity> result = new HashMap<>();
        for (ToolExecutionEntity execution : toolExecutions.findByExecutionId(executionId)) {
            UUID processItemId = execution.getToolProcessItemId();
            if (processItemId == null || result.put(processItemId, execution) != null) {
                throw new IllegalStateException(ERROR_MISMATCHED_TOOL_PROCESS);
            }
        }
        return Map.copyOf(result);
    }

    private void appendAssistant(
            ConversationMessage assistant,
            Map<UUID, ToolExecutionEntity> executionsByProcessItem,
            List<ModelInputMessage> result) {
        Map<Long, List<ToolThinkingProcessItem>> toolsByInvocation = new TreeMap<>();
        assistant.getThinkingProcess().stream()
                .sorted(Comparator.comparingLong(ThinkingProcessItem::sequence))
                .filter(ToolThinkingProcessItem.class::isInstance)
                .map(ToolThinkingProcessItem.class::cast)
                .forEach(
                        item ->
                                toolsByInvocation
                                        .computeIfAbsent(
                                                item.modelInvocationSequence(),
                                                ignored -> new ArrayList<>())
                                        .add(item));

        // 每个模型轮次先发 assistant tool_calls，再按节点顺序发送已经终态化的工具结果。
        for (List<ToolThinkingProcessItem> toolItems : toolsByInvocation.values()) {
            List<ModelToolCall> calls = new ArrayList<>(toolItems.size());
            for (ToolThinkingProcessItem item : toolItems) {
                validateToolItem(
                        assistant, item, executionsByProcessItem.get(item.processItemId()));
                calls.add(
                        new ModelToolCall(
                                item.toolCallId(), item.toolName(), inputNode(item.input())));
            }
            result.add(ModelInputMessage.assistantToolCalls(calls));
            for (ToolThinkingProcessItem item : toolItems) {
                if (isTerminal(item.status())) {
                    result.add(
                            ModelInputMessage.tool(
                                    outputContent(item.output()),
                                    item.toolCallId(),
                                    item.toolName()));
                }
            }
        }
        if (!assistant.getContent().isBlank()) {
            result.add(ModelInputMessage.plain(assistant.getRole(), assistant.getContent()));
        }
    }

    private void validateToolItem(
            ConversationMessage assistant,
            ToolThinkingProcessItem item,
            ToolExecutionEntity execution) {
        if (execution == null
                || !assistant
                        .getConversationMessageId()
                        .equals(execution.getAssistantConversationMessageId())
                || !item.toolExecutionId().equals(execution.getToolExecutionId())
                || !item.toolCallId().equals(execution.getToolCallId())
                || !item.toolName().equals(execution.getToolName())
                || item.modelInvocationSequence() != execution.getModelInvocationSequence()) {
            throw new IllegalStateException(ERROR_MISMATCHED_TOOL_PROCESS);
        }
    }

    private JsonNode inputNode(BoundedProcessPayload input) {
        if (!input.truncated()) {
            return json.valueToTree(input.value());
        }
        return json.valueToTree(payloadPreview(input));
    }

    private String outputContent(BoundedProcessPayload output) {
        if (output == null) {
            throw new IllegalStateException(ERROR_MISMATCHED_TOOL_PROCESS);
        }
        if (output.truncated()) {
            return json.writeValueAsString(payloadPreview(output));
        }
        JsonNode value = json.valueToTree(output.value());
        return value.isTextual() ? value.asString() : json.writeValueAsString(value);
    }

    private static Map<String, Object> payloadPreview(BoundedProcessPayload payload) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put(KEY_PREVIEW, payload.preview());
        preview.put(KEY_TRUNCATED, true);
        preview.put(KEY_ORIGINAL_SIZE_BYTES, payload.originalSizeBytes());
        preview.put(KEY_SHA256, payload.sha256());
        preview.put(KEY_CONTENT_TYPE, payload.contentType());
        return preview;
    }

    private static boolean isTerminal(ThinkingProcessStatus status) {
        return status == ThinkingProcessStatus.SUCCEEDED || status == ThinkingProcessStatus.FAILED;
    }
}
