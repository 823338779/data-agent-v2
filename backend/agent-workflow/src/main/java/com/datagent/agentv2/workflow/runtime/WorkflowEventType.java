package com.datagent.agentv2.workflow.runtime;

import com.fasterxml.jackson.annotation.JsonValue;

/** 供 SSE 展示的有限事件类型。 */
public enum WorkflowEventType {
    WORKFLOW_STARTED("workflow_started"),
    STEP_STARTED("step_started"),
    STEP_PROGRESS("step_progress"),
    STEP_SUCCEEDED("step_succeeded"),
    STEP_FAILED("step_failed"),
    STEP_RETRYING("step_retrying"),
    WORKFLOW_SUCCEEDED("workflow_succeeded"),
    WORKFLOW_FAILED("workflow_failed");

    private final String value;

    WorkflowEventType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
