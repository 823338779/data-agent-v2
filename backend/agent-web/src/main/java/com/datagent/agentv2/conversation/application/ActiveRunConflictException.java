package com.datagent.agentv2.conversation.application;

public final class ActiveRunConflictException extends RuntimeException {

    public ActiveRunConflictException() {
        super("Session already has an active run");
    }
}
