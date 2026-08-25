package com.datagent.agentv2.context.hierarchical.planning;

import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextToolCall;
import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.BoundedProcessPayload;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.MessageRole;
import com.datagent.agentv2.conversation.domain.ModelThinkingProcessItem;
import com.datagent.agentv2.conversation.domain.ThinkingProcessItem;
import com.datagent.agentv2.conversation.domain.ThinkingProcessStatus;
import com.datagent.agentv2.conversation.domain.ToolThinkingProcessItem;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 从真实会话消息和 thinking process 重建原子问答历史。 */
public final class HierarchicalConversationHistoryLoader {

    /** thinking-only 和 tool-call assistant 消息使用的空正文。 */
    private static final String EMPTY_CONTENT = "";

    /** execution 查询缺失时的稳定错误。 */
    private static final String ERROR_EXECUTION_NOT_FOUND = "Agent execution was not found";

    /** execution 不属于请求会话时的稳定错误。 */
    private static final String ERROR_EXECUTION_SESSION_MISMATCH =
            "Agent execution does not belong to the requested conversation session";

    /** 有界消息行违反顺序或会话约束时的稳定错误。 */
    private static final String ERROR_INVALID_HISTORY = "Conversation history is inconsistent";

    /** thinking tool 节点无法形成完整调用时的稳定错误。 */
    private static final String ERROR_MISMATCHED_TOOL_PROCESS =
            "Tool process item does not match its execution row";

    /** 截断载荷预览正文的协议字段名。 */
    private static final String KEY_PREVIEW = "preview";

    /** 截断载荷标记的协议字段名。 */
    private static final String KEY_TRUNCATED = "truncated";

    /** 截断前原始字节数的协议字段名。 */
    private static final String KEY_ORIGINAL_SIZE_BYTES = "originalSizeBytes";

    /** 完整载荷摘要的协议字段名。 */
    private static final String KEY_SHA256 = "sha256";

    /** 载荷内容类型的协议字段名。 */
    private static final String KEY_CONTENT_TYPE = "contentType";

    /** 失败 observation 状态的协议字段名。 */
    private static final String KEY_STATUS = "status";

    /** 失败 observation 错误码的协议字段名。 */
    private static final String KEY_ERROR_CODE = "errorCode";

    /** 失败 observation 安全错误信息的协议字段名。 */
    private static final String KEY_ERROR_MESSAGE = "errorMessage";

    /** 失败工具 observation 使用的稳定状态值。 */
    private static final String TOOL_STATUS_FAILED = "failed";

    /** 读取并校验请求 execution 所属会话的持久化服务。 */
    private final AgentExecutionService agentExecutions;

    /** 按会话读取全部真实消息的持久化服务。 */
    private final ConversationMessageService messages;

    /** 序列化工具 observation 和截断预览的 JSON 边界。 */
    private final ObjectMapper json;

    /** 将重建来源聚合为原子问答的分组器。 */
    private final ConversationTurnGrouper grouper;

    /** 固定持久化读取、JSON 转换和 turn 分组边界。 */
    public HierarchicalConversationHistoryLoader(
            AgentExecutionService agentExecutions,
            ConversationMessageService messages,
            ObjectMapper json,
            ConversationTurnGrouper grouper) {
        this.agentExecutions = Objects.requireNonNull(agentExecutions, "agentExecutions");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.json = Objects.requireNonNull(json, "json");
        this.grouper = Objects.requireNonNull(grouper, "grouper");
    }

    /** 校验触发 execution 后，确定性读取并重建会话全部轮次。 */
    public List<ConversationTurn> load(ContextExecutionRef executionRef) {
        ContextExecutionRef reference = Objects.requireNonNull(executionRef, "executionRef");
        AgentExecution execution = agentExecutions.findByExecutionId(reference.executionId());
        if (execution == null) {
            throw new IllegalStateException(ERROR_EXECUTION_NOT_FOUND);
        }
        if (!reference.conversationSessionId().equals(execution.getConversationSessionId())) {
            throw new IllegalStateException(ERROR_EXECUTION_SESSION_MISMATCH);
        }

        // 当前禁止执行期间追加消息，因此 Hierarchical 直接整合会话全部已持久化历史。
        List<ConversationMessage> rows =
                messages.findByConversationSessionId(reference.conversationSessionId());
        validateRows(reference, rows);
        List<ConversationTurnGrouper.SourceMessage> sources = new ArrayList<>(rows.size());

        // 每条持久化 assistant 中心行可展开多次模型调用，但仍只贡献一个源消息序号。
        for (ConversationMessage row : rows) {
            long sequence = row.getConversationMessageSequence();
            if (row.getRole() == MessageRole.USER) {
                sources.add(
                        ConversationTurnGrouper.SourceMessage.user(
                                sequence, ContextMessage.user(row.getContent())));
            } else {
                List<ContextMessage> rebuilt = rebuildAssistant(row);
                sources.add(ConversationTurnGrouper.SourceMessage.assistant(sequence, rebuilt));
            }
        }
        return grouper.groupSources(sources);
    }

    private static void validateRows(
            ContextExecutionRef reference, List<ConversationMessage> rows) {
        Objects.requireNonNull(rows, "rows");
        long previousSequence = 0L;
        for (ConversationMessage row : rows) {
            if (row == null
                    || !reference.conversationSessionId().equals(row.getConversationSessionId())
                    || row.getConversationMessageSequence() == null
                    || row.getConversationMessageSequence() <= previousSequence
                    || row.getRole() == null
                    || row.getContent() == null) {
                throw new IllegalStateException(ERROR_INVALID_HISTORY);
            }
            previousSequence = row.getConversationMessageSequence();
        }
    }

    private List<ContextMessage> rebuildAssistant(ConversationMessage assistant) {
        TreeMap<Long, InvocationParts> invocations = new TreeMap<>();
        List<ThinkingProcessItem> process =
                assistant.getThinkingProcess() == null
                        ? List.of()
                        : assistant.getThinkingProcess().stream()
                                .sorted(Comparator.comparingLong(ThinkingProcessItem::sequence))
                                .toList();

        // thinking 与 tool 节点按 model invocation 聚合，保留同一调用内的 process 顺序。
        for (ThinkingProcessItem item : process) {
            InvocationParts parts =
                    invocations.computeIfAbsent(
                            invocationSequence(item), ignored -> new InvocationParts());
            if (item instanceof ModelThinkingProcessItem thinking) {
                parts.thinking.append(thinking.content());
            } else if (item instanceof ToolThinkingProcessItem tool) {
                parts.tools.add(tool);
            }
        }

        List<ContextMessage> result = new ArrayList<>();
        Long finalInvocation = invocations.isEmpty() ? null : invocations.lastKey();
        String finalThinking = null;
        for (Map.Entry<Long, InvocationParts> entry : invocations.entrySet()) {
            InvocationParts parts = entry.getValue();
            String thinking = parts.thinking.isEmpty() ? null : parts.thinking.toString();
            if (parts.tools.isEmpty()) {
                if (entry.getKey().equals(finalInvocation) && !assistant.getContent().isBlank()) {
                    finalThinking = thinking;
                } else if (thinking != null) {
                    result.add(ContextMessage.assistant(EMPTY_CONTENT, thinking));
                }
                continue;
            }
            appendToolInvocation(parts.tools, thinking, result);
        }

        // 最终正文与最后一次无工具模型调用的 thinking 绑定；空 retry 占位不进入协议消息列表。
        if (!assistant.getContent().isBlank()) {
            result.add(ContextMessage.assistant(assistant.getContent(), finalThinking));
        }
        return List.copyOf(result);
    }

    private void appendToolInvocation(
            List<ToolThinkingProcessItem> items, String thinking, List<ContextMessage> result) {
        List<ContextToolCall> calls = new ArrayList<>(items.size());

        // 同一 invocation 先形成完整 assistant tool_calls，再依节点顺序追加 terminal observations。
        for (ToolThinkingProcessItem item : items) {
            validateToolItem(item);
            calls.add(
                    new ContextToolCall(
                            item.toolCallId(), item.toolName(), inputArguments(item.input())));
        }
        result.add(
                new ContextMessage(
                        ContextMessage.Role.ASSISTANT, EMPTY_CONTENT, thinking, calls, null, null));
        for (ToolThinkingProcessItem item : items) {
            if (isTerminal(item.status())) {
                result.add(
                        ContextMessage.tool(
                                outputContent(item), item.toolCallId(), item.toolName()));
            }
        }
    }

    private static long invocationSequence(ThinkingProcessItem item) {
        if (item instanceof ModelThinkingProcessItem thinking) {
            return thinking.modelInvocationSequence();
        }
        return ((ToolThinkingProcessItem) item).modelInvocationSequence();
    }

    private static void validateToolItem(ToolThinkingProcessItem item) {
        // 冻结历史只接受 thinking process 中可同时重建 call 与 observation 的 terminal 节点。
        if (!isTerminal(item.status())
                || item.toolCallId().isBlank()
                || item.toolName().isBlank()) {
            throw new IllegalStateException(ERROR_MISMATCHED_TOOL_PROCESS);
        }
    }

    private Map<String, Object> inputArguments(BoundedProcessPayload input) {
        Objects.requireNonNull(input, "input");
        if (input.truncated()) {
            return payloadPreview(input);
        }
        if (!(input.value() instanceof Map<?, ?> persistedArguments)) {
            throw new IllegalStateException(ERROR_MISMATCHED_TOOL_PROCESS);
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : persistedArguments.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalStateException(ERROR_MISMATCHED_TOOL_PROCESS);
            }
            arguments.put(key, entry.getValue());
        }
        return arguments;
    }

    private String outputContent(ToolThinkingProcessItem item) {
        BoundedProcessPayload output = item.output();
        if (output == null && item.status() == ThinkingProcessStatus.FAILED) {
            var failure = json.createObjectNode();
            failure.put(KEY_STATUS, TOOL_STATUS_FAILED);
            failure.put(KEY_ERROR_CODE, item.errorCode());
            failure.put(KEY_ERROR_MESSAGE, item.errorMessage());
            return json.writeValueAsString(failure);
        }
        if (output == null) {
            throw new IllegalStateException(ERROR_MISMATCHED_TOOL_PROCESS);
        }
        if (output.truncated()) {
            return json.writeValueAsString(payloadPreview(output));
        }
        JsonNode value = json.valueToTree(output.value());
        return value.isString() ? value.asString() : json.writeValueAsString(value);
    }

    private static Map<String, Object> payloadPreview(BoundedProcessPayload payload) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put(KEY_PREVIEW, payload.preview());
        preview.put(KEY_TRUNCATED, true);
        preview.put(KEY_ORIGINAL_SIZE_BYTES, payload.originalSizeBytes());
        preview.put(KEY_SHA256, payload.sha256());
        preview.put(KEY_CONTENT_TYPE, payload.contentType());
        return Map.copyOf(preview);
    }

    private static boolean isTerminal(ThinkingProcessStatus status) {
        return status == ThinkingProcessStatus.SUCCEEDED || status == ThinkingProcessStatus.FAILED;
    }

    /** 单次模型调用内按 process 顺序累积的 thinking 与工具节点。 */
    private static final class InvocationParts {

        /** 同一模型调用可能分段持久化的 thinking 内容。 */
        private final StringBuilder thinking = new StringBuilder();

        /** 同一模型调用按 process 顺序产生的工具调用。 */
        private final List<ToolThinkingProcessItem> tools = new ArrayList<>();
    }
}
