package com.datagent.agentv2.context.hierarchical.planning;

import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextToolCall;
import com.datagent.agentv2.conversation.context.ContextToolDefinition;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 在没有稳定 provider tokenizer 时，按字符和协议结构保守估算完整模型输入。 */
public final class ConversationTokenEstimator {

    /** 每条消息外层分隔符的保守开销。 */
    private static final int MESSAGE_DELIMITER_TOKENS = 2;

    /** 消息角色字段边界的保守开销。 */
    private static final int ROLE_BOUNDARY_TOKENS = 1;

    /** 消息正文边界的保守开销。 */
    private static final int CONTENT_BOUNDARY_TOKENS = 1;

    /** assistant thinking 字段边界的保守开销。 */
    private static final int THINKING_BOUNDARY_TOKENS = 2;

    /** 单个工具调用对象边界的保守开销。 */
    private static final int TOOL_CALL_BOUNDARY_TOKENS = 2;

    /** 工具调用 ID 字段边界的保守开销。 */
    private static final int TOOL_CALL_ID_BOUNDARY_TOKENS = 1;

    /** 工具名称字段边界的保守开销。 */
    private static final int TOOL_NAME_BOUNDARY_TOKENS = 1;

    /** 单个工具定义外层结构的保守开销。 */
    private static final int TOOL_DEFINITION_BOUNDARY_TOKENS = 3;

    /** 摘要文档进入模型输入时的固定包装开销。 */
    private static final int SUMMARY_WRAPPER_TOKENS = 4;

    /** JSON object 的花括号边界开销。 */
    private static final int JSON_OBJECT_BOUNDARY_TOKENS = 2;

    /** JSON array 的方括号边界开销。 */
    private static final int JSON_ARRAY_BOUNDARY_TOKENS = 2;

    /** JSON key/value 配对分隔开销。 */
    private static final int JSON_PAIR_BOUNDARY_TOKENS = 1;

    /** JSON 相邻成员或元素的逗号开销。 */
    private static final int JSON_ITEM_DELIMITER_TOKENS = 1;

    /** JSON string 的引号边界开销。 */
    private static final int JSON_STRING_BOUNDARY_TOKENS = 2;

    /** JSON null 字面值的最小开销。 */
    private static final int JSON_NULL_TOKENS = 1;

    /** 摘要 JSON 中单个结构标点的附加保守开销。 */
    private static final int JSON_PUNCTUATION_TOKENS = 1;

    /** 估算溢出时使用的饱和值。 */
    private static final int MAX_TOKEN_ESTIMATE = Integer.MAX_VALUE;

    /** 估算固定协议、system、工具定义和本次动态消息组成的完整输入。 */
    public int estimate(ConversationContextRequest request, List<ContextMessage> messages) {
        ConversationContextRequest contextRequest = Objects.requireNonNull(request, "request");
        int estimate = contextRequest.budget().fixedInputTokens();

        // safety reserve 只参与物理硬上限，不是模型输入；这里仅累加实际发送的固定与动态内容。
        estimate = add(estimate, estimateMessages(contextRequest.fixedSystemMessages()));
        estimate = add(estimate, estimateTools(contextRequest.toolDefinitions()));
        return add(estimate, estimateMessages(messages));
    }

    /** 估算有序消息的正文、thinking、工具调用和 observation 协议边界。 */
    public int estimateMessages(List<ContextMessage> messages) {
        Objects.requireNonNull(messages, "messages");
        int estimate = 0;
        for (ContextMessage message : messages) {
            estimate = add(estimate, estimateMessage(Objects.requireNonNull(message, "message")));
        }
        return estimate;
    }

    /** 估算每次调用固定携带的工具定义与 JSON 参数约束。 */
    public int estimateTools(List<ContextToolDefinition> tools) {
        Objects.requireNonNull(tools, "tools");
        int estimate = 0;
        for (ContextToolDefinition tool : tools) {
            ContextToolDefinition definition = Objects.requireNonNull(tool, "tool definition");
            int toolEstimate = TOOL_DEFINITION_BOUNDARY_TOKENS;
            toolEstimate = add(toolEstimate, estimateText(definition.name()));
            toolEstimate = add(toolEstimate, estimateText(definition.description()));
            estimate = add(estimate, add(toolEstimate, estimateJsonValue(definition.parameters())));
        }
        return estimate;
    }

    /** 估算带摘要包装器的 JSON 文档，并额外覆盖结构标点。 */
    public int estimateSummary(String summaryDocument) {
        String document = Objects.requireNonNull(summaryDocument, "summaryDocument");
        return add(
                add(SUMMARY_WRAPPER_TOKENS, estimateText(document)),
                estimateJsonPunctuation(document));
    }

    private int estimateMessage(ContextMessage message) {
        int estimate = add(MESSAGE_DELIMITER_TOKENS, ROLE_BOUNDARY_TOKENS);
        estimate = add(estimate, estimateText(message.role().name()));
        estimate = add(estimate, CONTENT_BOUNDARY_TOKENS);
        estimate = add(estimate, estimateText(message.content()));
        if (message.thinking() != null) {
            estimate = add(estimate, THINKING_BOUNDARY_TOKENS);
            estimate = add(estimate, estimateText(message.thinking()));
        }
        if (message.toolCallId() != null) {
            estimate = add(estimate, TOOL_CALL_ID_BOUNDARY_TOKENS);
            estimate = add(estimate, estimateText(message.toolCallId()));
        }
        if (message.toolName() != null) {
            estimate = add(estimate, TOOL_NAME_BOUNDARY_TOKENS);
            estimate = add(estimate, estimateText(message.toolName()));
        }
        for (ContextToolCall toolCall : message.toolCalls()) {
            estimate = add(estimate, estimateToolCall(toolCall));
        }
        return estimate;
    }

    private int estimateToolCall(ContextToolCall toolCall) {
        ContextToolCall call = Objects.requireNonNull(toolCall, "tool call");
        int estimate = TOOL_CALL_BOUNDARY_TOKENS;
        estimate = add(estimate, add(TOOL_CALL_ID_BOUNDARY_TOKENS, estimateText(call.id())));
        estimate = add(estimate, add(TOOL_NAME_BOUNDARY_TOKENS, estimateText(call.name())));
        return add(estimate, estimateJsonValue(call.arguments()));
    }

    private int estimateJsonValue(Object value) {
        // null 与 JSON string-like 标量分别覆盖字面值或引号边界。
        if (value == null) {
            return JSON_NULL_TOKENS;
        }
        if (value instanceof CharSequence text) {
            return add(JSON_STRING_BOUNDARY_TOKENS, estimateText(text.toString()));
        }
        if (value instanceof Character || value instanceof Enum<?> || value instanceof UUID) {
            return add(JSON_STRING_BOUNDARY_TOKENS, estimateText(String.valueOf(value)));
        }

        // object 递归计入 key、value、冒号与相邻成员分隔符。
        if (value instanceof Map<?, ?> object) {
            int estimate = JSON_OBJECT_BOUNDARY_TOKENS;
            boolean hasPrevious = false;
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                if (hasPrevious) {
                    estimate = add(estimate, JSON_ITEM_DELIMITER_TOKENS);
                }
                estimate = add(estimate, estimateJsonValue(String.valueOf(entry.getKey())));
                estimate = add(estimate, JSON_PAIR_BOUNDARY_TOKENS);
                estimate = add(estimate, estimateJsonValue(entry.getValue()));
                hasPrevious = true;
            }
            return estimate;
        }

        // collection 和数组都按 JSON array 计入外层边界与元素分隔符。
        if (value instanceof Iterable<?> values) {
            int estimate = JSON_ARRAY_BOUNDARY_TOKENS;
            boolean hasPrevious = false;
            for (Object item : values) {
                if (hasPrevious) {
                    estimate = add(estimate, JSON_ITEM_DELIMITER_TOKENS);
                }
                estimate = add(estimate, estimateJsonValue(item));
                hasPrevious = true;
            }
            return estimate;
        }

        if (value.getClass().isArray()) {
            int estimate = JSON_ARRAY_BOUNDARY_TOKENS;
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                if (index > 0) {
                    estimate = add(estimate, JSON_ITEM_DELIMITER_TOKENS);
                }
                estimate = add(estimate, estimateJsonValue(Array.get(value, index)));
            }
            return estimate;
        }

        // 数值和布尔等其余标量按规范文本长度计量。
        return estimateText(String.valueOf(value));
    }

    private static int estimateText(String text) {
        return Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8).length;
    }

    private static int estimateJsonPunctuation(String document) {
        int estimate = 0;
        for (int offset = 0; offset < document.length(); ) {
            int codePoint = document.codePointAt(offset);
            if (codePoint == '{'
                    || codePoint == '}'
                    || codePoint == '['
                    || codePoint == ']'
                    || codePoint == ':'
                    || codePoint == ','
                    || codePoint == '"') {
                estimate = add(estimate, JSON_PUNCTUATION_TOKENS);
            }
            offset += Character.charCount(codePoint);
        }
        return estimate;
    }

    private static int add(int left, int right) {
        if (left >= MAX_TOKEN_ESTIMATE - right) {
            return MAX_TOKEN_ESTIMATE;
        }
        return left + right;
    }
}
