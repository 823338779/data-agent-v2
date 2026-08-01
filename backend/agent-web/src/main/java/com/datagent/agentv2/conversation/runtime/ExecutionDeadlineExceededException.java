package com.datagent.agentv2.conversation.runtime;

import static com.datagent.agentv2.conversation.AgentExecutionConstants.ERROR_EXECUTION_BUDGET_EXHAUSTED_PREFIX;

public final class ExecutionDeadlineExceededException extends RuntimeException {

    public ExecutionDeadlineExceededException(String executionId) {
        super(ERROR_EXECUTION_BUDGET_EXHAUSTED_PREFIX + executionId);
    }
}
