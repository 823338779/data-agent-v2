package com.datagent.agentv2.conversation.runtime;

import static com.datagent.agentv2.conversation.RunConstants.ERROR_RUN_OWNERSHIP_LOST_PREFIX;

public final class RunOwnershipLostException extends RuntimeException {

    public RunOwnershipLostException(String runId) {
        super(ERROR_RUN_OWNERSHIP_LOST_PREFIX + runId);
    }
}
