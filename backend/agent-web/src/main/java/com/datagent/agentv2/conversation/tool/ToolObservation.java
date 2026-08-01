package com.datagent.agentv2.conversation.tool;

import tools.jackson.databind.JsonNode;

public record ToolObservation(
        Status status, JsonNode content, String failureCode, String failureMessage) {

    public ToolObservation {
        content = content == null ? null : content.deepCopy();
    }

    public static ToolObservation succeeded(JsonNode content) {
        return new ToolObservation(Status.SUCCEEDED, content, null, null);
    }

    public static ToolObservation failed(String code, String message) {
        return new ToolObservation(Status.FAILED, null, code, message);
    }

    public enum Status {
        SUCCEEDED,
        FAILED
    }
}
