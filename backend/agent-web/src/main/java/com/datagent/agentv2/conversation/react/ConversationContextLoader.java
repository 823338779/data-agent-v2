package com.datagent.agentv2.conversation.react;

import com.datagent.agentv2.persistence.entity.ConversationMessageEntity;
import com.datagent.agentv2.persistence.entity.ToolExecutionEntity;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ToolExecutionService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class ConversationContextLoader {

    private static final int TOOL_CALL_DOCUMENT_VERSION = 1;
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_TOOL = "tool";
    private static final String ERROR_INVALID_TOOL_CALLS = "Assistant tool calls are invalid";
    private static final String ERROR_MISMATCHED_OBSERVATION =
            "Tool observation does not match its assistant tool call";

    private final ConversationMessageService messages;
    private final ToolExecutionService toolExecutions;
    private final ObjectMapper json;

    public List<ModelInputMessage> load(
            String executionId, long inputUpToConversationMessageSequence) {
        List<ConversationMessageEntity> rows =
                messages.findContextByExecutionIdUpTo(
                        executionId, inputUpToConversationMessageSequence);
        Map<String, ToolExecutionEntity> executionsById = executionIndex(executionId);
        Map<String, Map<String, ModelToolCall>> callsByAssistantMessage = new HashMap<>();
        List<ModelInputMessage> result = new ArrayList<>(rows.size());

        // 逐条重放完整消息边界，确保 Observation 只能引用此前已归档的 assistant call。
        for (ConversationMessageEntity row : rows) {
            if (ROLE_ASSISTANT.equals(row.getRole()) && row.getToolCalls() != null) {
                List<ModelToolCall> calls = parseToolCalls(row.getToolCalls());
                callsByAssistantMessage.put(row.getConversationMessageId(), callIndex(calls));
                result.add(ModelInputMessage.assistantToolCalls(calls));
            } else if (ROLE_TOOL.equals(row.getRole())) {
                validateObservation(row, executionsById, callsByAssistantMessage);
                result.add(
                        ModelInputMessage.tool(
                                row.getContent(), row.getToolCallId(), row.getToolName()));
            } else {
                result.add(ModelInputMessage.plain(row.getRole(), row.getContent()));
            }
        }
        return List.copyOf(result);
    }

    private Map<String, ToolExecutionEntity> executionIndex(String executionId) {
        Map<String, ToolExecutionEntity> result = new HashMap<>();
        for (ToolExecutionEntity execution : toolExecutions.findByExecutionId(executionId)) {
            result.put(execution.getToolExecutionId(), execution);
        }
        return result;
    }

    private List<ModelToolCall> parseToolCalls(String document) {
        try {
            JsonNode root = json.readTree(document);
            JsonNode calls = root.path("calls");
            if (root.path("version").asInt() != TOOL_CALL_DOCUMENT_VERSION || !calls.isArray()) {
                throw new IllegalStateException(ERROR_INVALID_TOOL_CALLS);
            }
            List<ModelToolCall> result = new ArrayList<>();
            for (JsonNode call : calls) {
                String toolCallId = requiredText(call, "id");
                String name = requiredText(call, "name");
                JsonNode arguments = call.get("arguments");
                if (arguments == null || !arguments.isObject()) {
                    throw new IllegalStateException(ERROR_INVALID_TOOL_CALLS);
                }
                result.add(new ModelToolCall(toolCallId, name, arguments));
            }
            return List.copyOf(result);
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(ERROR_INVALID_TOOL_CALLS, failure);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asString().isBlank()) {
            throw new IllegalStateException(ERROR_INVALID_TOOL_CALLS);
        }
        return value.asString();
    }

    private static Map<String, ModelToolCall> callIndex(List<ModelToolCall> calls) {
        Map<String, ModelToolCall> result = new HashMap<>();
        for (ModelToolCall call : calls) {
            if (result.put(call.toolCallId(), call) != null) {
                throw new IllegalStateException(ERROR_INVALID_TOOL_CALLS);
            }
        }
        return Map.copyOf(result);
    }

    private static void validateObservation(
            ConversationMessageEntity observation,
            Map<String, ToolExecutionEntity> executionsById,
            Map<String, Map<String, ModelToolCall>> callsByAssistantMessage) {
        ToolExecutionEntity execution = executionsById.get(observation.getToolExecutionId());
        if (execution == null) {
            throw new IllegalStateException(ERROR_MISMATCHED_OBSERVATION);
        }
        ModelToolCall call =
                callsByAssistantMessage
                        .getOrDefault(execution.getAssistantConversationMessageId(), Map.of())
                        .get(execution.getToolCallId());
        if (call == null
                || !execution.getToolCallId().equals(observation.getToolCallId())
                || !execution.getToolName().equals(observation.getToolName())
                || !call.name().equals(observation.getToolName())) {
            throw new IllegalStateException(ERROR_MISMATCHED_OBSERVATION);
        }
    }
}
