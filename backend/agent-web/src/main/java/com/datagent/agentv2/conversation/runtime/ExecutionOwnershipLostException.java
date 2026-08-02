package com.datagent.agentv2.conversation.runtime;

import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_EXECUTION_OWNERSHIP_LOST_PREFIX;

public final class ExecutionOwnershipLostException extends RuntimeException {

    public ExecutionOwnershipLostException(String executionId) {
        super(ERROR_EXECUTION_OWNERSHIP_LOST_PREFIX + executionId);
    }
}
