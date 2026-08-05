package com.datagent.agentv2.conversation.context;

import java.util.Map;
import java.util.Objects;

/** 与供应商无关的历史工具调用。 */
public record ContextToolCall(String id, String name, Map<String, Object> arguments) {

    public ContextToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        arguments = Map.copyOf(arguments);
    }
}
