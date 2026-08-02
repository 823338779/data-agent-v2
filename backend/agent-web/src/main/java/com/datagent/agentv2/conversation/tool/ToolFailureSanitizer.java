package com.datagent.agentv2.conversation.tool;

import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public final class ToolFailureSanitizer {

    private static final String MESSAGE_INVALID_ARGUMENTS = "Tool arguments are invalid";
    private static final String MESSAGE_ACCESS_DENIED = "Tool access was denied";
    private static final String MESSAGE_TIMEOUT = "Tool execution timed out";
    private static final String MESSAGE_TOOL_ERROR = "Tool execution failed";

    public ToolFailureSanitizer(ObjectMapper ignored) {}

    public ToolExecutionResult sanitize(Throwable failure) {
        if (failure instanceof AgentTool.BusinessFailure business) {
            return ToolExecutionResult.failed(business.code(), business.safeMessage());
        }
        if (failure instanceof IllegalArgumentException) {
            return ToolExecutionResult.failed(
                    ToolFailureCode.INVALID_ARGUMENTS.value(), MESSAGE_INVALID_ARGUMENTS);
        }
        if (failure instanceof SecurityException) {
            return ToolExecutionResult.failed(
                    ToolFailureCode.ACCESS_DENIED.value(), MESSAGE_ACCESS_DENIED);
        }
        if (failure instanceof TimeoutException) {
            return ToolExecutionResult.failed(ToolFailureCode.TIMEOUT.value(), MESSAGE_TIMEOUT);
        }
        return ToolExecutionResult.failed(ToolFailureCode.TOOL_ERROR.value(), MESSAGE_TOOL_ERROR);
    }
}
