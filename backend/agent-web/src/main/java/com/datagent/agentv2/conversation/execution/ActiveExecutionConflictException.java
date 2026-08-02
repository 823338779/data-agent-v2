package com.datagent.agentv2.conversation.execution;

import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_ACTIVE_EXECUTION_CONFLICT;

public final class ActiveExecutionConflictException extends RuntimeException {

    public ActiveExecutionConflictException() {
        super(ERROR_ACTIVE_EXECUTION_CONFLICT);
    }
}
