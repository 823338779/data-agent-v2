package com.datagent.agentv2.conversation.runtime;

/** 执行已被取消、终止或超过硬截止时间。 */
public final class ExecutionStoppedException extends RuntimeException {

    public ExecutionStoppedException(String message) {
        super(message);
    }
}
