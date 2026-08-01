package com.datagent.agentv2.conversation.model;

/** provider 流未形成完整终态时抛出的稳定异常边界。 */
public final class ModelStreamInterruptedException extends RuntimeException {

    public ModelStreamInterruptedException(String message) {
        super(message);
    }

    public ModelStreamInterruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
