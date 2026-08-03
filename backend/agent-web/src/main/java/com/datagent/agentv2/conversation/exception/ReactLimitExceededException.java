package com.datagent.agentv2.conversation.exception;

public final class ReactLimitExceededException extends RuntimeException {

    public ReactLimitExceededException(String message) {
        super(message);
    }
}
