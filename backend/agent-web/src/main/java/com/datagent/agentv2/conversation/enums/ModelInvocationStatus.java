package com.datagent.agentv2.conversation.enums;

/** 模型调用持久化生命周期状态。 */
public enum ModelInvocationStatus {
    PREPARED("prepared"),
    STREAMING("streaming"),
    COMPLETED("completed");

    private final String value;

    ModelInvocationStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean matches(String candidate) {
        return value.equals(candidate);
    }
}
