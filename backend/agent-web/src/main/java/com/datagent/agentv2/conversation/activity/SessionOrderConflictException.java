package com.datagent.agentv2.conversation.activity;

/** 表示跨设备提交的会话排序版本已经落后于服务端。 */
public final class SessionOrderConflictException extends RuntimeException {

    private static final String ERROR_MESSAGE = "Conversation ordering version is stale";

    public SessionOrderConflictException() {
        super(ERROR_MESSAGE);
    }
}
