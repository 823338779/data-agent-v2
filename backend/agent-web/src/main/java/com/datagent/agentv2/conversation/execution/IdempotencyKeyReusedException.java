package com.datagent.agentv2.conversation.execution;

import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_IDEMPOTENCY_KEY_REUSED;

public final class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super(ERROR_IDEMPOTENCY_KEY_REUSED);
    }
}
