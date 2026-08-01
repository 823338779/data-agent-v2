package com.datagent.agentv2.conversation.application;

import static com.datagent.agentv2.conversation.AgentExecutionConstants.ERROR_IDEMPOTENCY_KEY_REUSED;

public final class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super(ERROR_IDEMPOTENCY_KEY_REUSED);
    }
}
