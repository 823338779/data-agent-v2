package com.datagent.agentv2.conversation.runtime;

import static com.datagent.agentv2.conversation.RunConstants.ERROR_RUN_BUDGET_EXHAUSTED_PREFIX;

public final class RunDeadlineExceededException extends RuntimeException {

    public RunDeadlineExceededException(String runId) {
        super(ERROR_RUN_BUDGET_EXHAUSTED_PREFIX + runId);
    }
}
