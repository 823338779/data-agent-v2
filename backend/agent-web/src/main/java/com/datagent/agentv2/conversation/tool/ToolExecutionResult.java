package com.datagent.agentv2.conversation.tool;

import tools.jackson.databind.JsonNode;

public record ToolExecutionResult(
        Status status, JsonNode content, String failureCode, String failureMessage) {

    public ToolExecutionResult {
        content = content == null ? null : content.deepCopy();
    }

    public static ToolExecutionResult succeeded(JsonNode content) {
        return new ToolExecutionResult(Status.SUCCEEDED, content, null, null);
    }

    public static ToolExecutionResult failed(String code, String message) {
        return new ToolExecutionResult(Status.FAILED, null, code, message);
    }

    public enum Status {
        SUCCEEDED,
        FAILED
    }
}
