package com.datagent.agentv2.context.lsm.planning;

import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextToolCall;
import com.datagent.agentv2.conversation.context.ContextToolDefinition;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import java.lang.reflect.Array;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 在没有稳定 provider tokenizer 时，按协议结构保守估算模型输入 token。 */
public final class ConversationTokenEstimator {

    private static final int MESSAGE_DELIMITER_TOKENS = 2;
    private static final int ROLE_BOUNDARY_TOKENS = 1;
    private static final int CONTENT_BOUNDARY_TOKENS = 1;
    private static final int THINKING_BOUNDARY_TOKENS = 2;
    private static final int TOOL_CALL_BOUNDARY_TOKENS = 2;
    private static final int TOOL_CALL_ID_BOUNDARY_TOKENS = 1;
    private static final int TOOL_NAME_BOUNDARY_TOKENS = 1;
    private static final int TOOL_DEFINITION_BOUNDARY_TOKENS = 3;
    private static final int SUMMARY_WRAPPER_TOKENS = 4;
    private static final int JSON_OBJECT_BOUNDARY_TOKENS = 2;
    private static final int JSON_ARRAY_BOUNDARY_TOKENS = 2;
    private static final int JSON_PAIR_BOUNDARY_TOKENS = 1;
    private static final int JSON_ITEM_DELIMITER_TOKENS = 1;
    private static final int JSON_STRING_BOUNDARY_TOKENS = 2;
    private static final int JSON_NULL_TOKENS = 1;
    private static final int JSON_PUNCTUATION_TOKENS = 1;
    private static final int MAX_TOKEN_ESTIMATE = Integer.MAX_VALUE;

    /** 估算任意有序模型消息的正文、thinking 与工具协议边界。 */
    public int estimateMessages(List<ContextMessage> messages) {
        Objects.requireNonNull(messages, "messages");
        int estimate = 0;
        for (ContextMessage message : messages) {
            estimate = add(estimate, estimateMessage(Objects.requireNonNull(message, "message")));
        }
        return estimate;
    }

    /** 估算每次调用固定携带的工具定义及其 JSON 参数约束。 */
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

    /** 估算带有不可信摘要包装器的 JSON 文档，额外覆盖 JSON 标点。 */
    public int estimateSummaryDocument(String jsonDocument) {
        String document = Objects.requireNonNull(jsonDocument, "jsonDocument");
        return add(
                add(SUMMARY_WRAPPER_TOKENS, estimateText(document)),
                estimateJsonPunctuation(document));
    }

    /** 估算固定 system、工具定义、协议固定开销和本次选择的业务消息。 */
    public int estimateCompleteInput(
            ConversationContextRequest request, List<ContextMessage> messages) {
        ConversationContextRequest contextRequest = Objects.requireNonNull(request, "request");
        int estimate = contextRequest.budget().fixedInputTokens();
        estimate = add(estimate, estimateMessages(contextRequest.fixedSystemMessages()));
        estimate = add(estimate, estimateTools(contextRequest.toolDefinitions()));
        return add(estimate, estimateMessages(messages));
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
        if (value == null) {
            return JSON_NULL_TOKENS;
        }
        if (value instanceof CharSequence text) {
            return add(JSON_STRING_BOUNDARY_TOKENS, estimateText(text.toString()));
        }
        if (value instanceof Character || value instanceof Enum<?> || value instanceof UUID) {
            // Jackson 会把字符、枚举和 UUID 写成 JSON string，必须计入引号边界。
            return add(JSON_STRING_BOUNDARY_TOKENS, estimateText(String.valueOf(value)));
        }
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
        return estimateText(String.valueOf(value));
    }

    private static int estimateText(String text) {
        return Objects.requireNonNull(text, "text").codePointCount(0, text.length());
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
