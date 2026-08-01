package com.datagent.agentv2.conversation.application;

public final class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("Idempotency-Key was already used for another payload");
    }
}
