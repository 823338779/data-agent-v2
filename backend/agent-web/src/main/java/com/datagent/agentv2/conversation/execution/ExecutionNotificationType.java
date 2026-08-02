package com.datagent.agentv2.conversation.execution;

/** Agent 执行控制 Outbox 的通知类型。 */
public enum ExecutionNotificationType {
    CANCELLATION_REQUESTED("execution_cancellation_requested");

    private final String value;

    ExecutionNotificationType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
