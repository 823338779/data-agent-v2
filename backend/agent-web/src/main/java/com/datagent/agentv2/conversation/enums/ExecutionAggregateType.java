package com.datagent.agentv2.conversation.enums;

/** Outbox 消息所引用的业务聚合类型。 */
public enum ExecutionAggregateType {
    AGENT_EXECUTION("agent_execution");

    private final String value;

    ExecutionAggregateType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
