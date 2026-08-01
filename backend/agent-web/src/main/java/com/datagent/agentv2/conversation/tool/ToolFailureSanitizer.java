package com.datagent.agentv2.conversation.tool;

import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public final class ToolFailureSanitizer {

    private static final String CODE_INVALID_ARGUMENTS = "invalid_arguments";
    private static final String CODE_ACCESS_DENIED = "access_denied";
    private static final String CODE_TIMEOUT = "timeout";
    private static final String CODE_TOOL_ERROR = "tool_error";
    private static final String MESSAGE_INVALID_ARGUMENTS = "Tool arguments are invalid";
    private static final String MESSAGE_ACCESS_DENIED = "Tool access was denied";
    private static final String MESSAGE_TIMEOUT = "Tool execution timed out";
    private static final String MESSAGE_TOOL_ERROR = "Tool execution failed";

    public ToolFailureSanitizer(ObjectMapper ignored) {}

    public ToolObservation sanitize(Throwable failure) {
        if (failure instanceof AgentTool.BusinessFailure business) {
            return ToolObservation.failed(business.code(), business.safeMessage());
        }
        if (failure instanceof IllegalArgumentException) {
            return ToolObservation.failed(CODE_INVALID_ARGUMENTS, MESSAGE_INVALID_ARGUMENTS);
        }
        if (failure instanceof SecurityException) {
            return ToolObservation.failed(CODE_ACCESS_DENIED, MESSAGE_ACCESS_DENIED);
        }
        if (failure instanceof TimeoutException) {
            return ToolObservation.failed(CODE_TIMEOUT, MESSAGE_TIMEOUT);
        }
        return ToolObservation.failed(CODE_TOOL_ERROR, MESSAGE_TOOL_ERROR);
    }
}
