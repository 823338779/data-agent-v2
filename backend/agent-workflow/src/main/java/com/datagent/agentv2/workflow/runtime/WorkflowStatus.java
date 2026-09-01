package com.datagent.agentv2.workflow.runtime;

import com.fasterxml.jackson.annotation.JsonValue;

/** Workflow Run 状态；步骤成功不等于 Run 终态。 */
public enum WorkflowStatus {
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    TIMED_OUT("timed_out");

    private final String value;

    WorkflowStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public boolean terminal() {
        return this != RUNNING;
    }
}
