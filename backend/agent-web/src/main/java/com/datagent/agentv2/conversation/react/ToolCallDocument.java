package com.datagent.agentv2.conversation.react;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** assistant tool_calls 中稳定、版本化的单个 provider 调用。 */
public record ToolCallDocument(String id, String name, JsonNode arguments) {

    public ToolCallDocument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arguments, "arguments");
        arguments = arguments.deepCopy();
    }
}
