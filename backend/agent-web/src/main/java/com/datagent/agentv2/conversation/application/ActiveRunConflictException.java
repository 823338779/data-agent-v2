package com.datagent.agentv2.conversation.application;

import static com.datagent.agentv2.conversation.RunConstants.ERROR_ACTIVE_RUN_CONFLICT;

public final class ActiveRunConflictException extends RuntimeException {

    public ActiveRunConflictException() {
        super(ERROR_ACTIVE_RUN_CONFLICT);
    }
}
