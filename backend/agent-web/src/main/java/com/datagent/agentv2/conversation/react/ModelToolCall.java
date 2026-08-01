package com.datagent.agentv2.conversation.react;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * assistant tool_calls 中稳定、版本化的单个 provider 调用。
 *
 * @param toolCallId 模型提供方为本次工具调用分配的标识
 * @param name 待调用的工具名称
 * @param arguments 模型生成的工具调用参数
 */
public record ModelToolCall(String toolCallId, String name, JsonNode arguments) {

    public ModelToolCall {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arguments, "arguments");
        arguments = arguments.deepCopy();
    }
}
