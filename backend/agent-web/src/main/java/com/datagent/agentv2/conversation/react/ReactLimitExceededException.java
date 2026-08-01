package com.datagent.agentv2.conversation.react;

public final class ReactLimitExceededException extends RuntimeException {

    public ReactLimitExceededException(String message) {
        super(message);
    }
}
