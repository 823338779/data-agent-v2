package com.datagent.agentv2.conversation.context;

import java.util.Map;
import java.util.Objects;

/** 上下文预算需要计量的稳定工具定义。 */
public record ContextToolDefinition(
        String name, String description, Map<String, Object> parameters) {

    public ContextToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        parameters = Map.copyOf(parameters);
    }
}
