package com.datagent.agentv2.conversation.activity;

import com.datagent.agentv2.conversation.execution.ExecutionStatus;
import com.fasterxml.jackson.annotation.JsonValue;

/** 会话列表展示使用的活动状态。 */
public enum SessionActivityStatus {
    IDLE("idle"),
    QUEUED("queued"),
    RUNNING("running"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    DONE("done"),
    LOST("lost"),
    WAITING_FOR_USER("waiting_for_user"),
    WAITING_CONFIRMATION("waiting_confirmation");

    private final String value;

    SessionActivityStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public static SessionActivityStatus fromExecutionStatus(ExecutionStatus status) {
        return valueOf(status.name());
    }
}
