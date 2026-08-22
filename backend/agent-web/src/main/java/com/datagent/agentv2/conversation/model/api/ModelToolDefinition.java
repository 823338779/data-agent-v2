package com.datagent.agentv2.conversation.model.api;

import java.util.Map;
import java.util.Objects;

public record ModelToolDefinition(String name, String description, Map<String, Object> parameters) {

    public ModelToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        parameters = Map.copyOf(parameters);
    }
}
