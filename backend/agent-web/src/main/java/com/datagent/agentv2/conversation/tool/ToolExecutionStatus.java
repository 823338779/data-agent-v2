package com.datagent.agentv2.conversation.tool;

/** 工具调用持久化生命周期状态。 */
public enum ToolExecutionStatus {
    PREPARED("prepared"),
    EXECUTING("executing"),
    SUCCEEDED("succeeded"),
    FAILED("failed");

    private final String value;

    ToolExecutionStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean matches(String candidate) {
        return value.equals(candidate);
    }
}
