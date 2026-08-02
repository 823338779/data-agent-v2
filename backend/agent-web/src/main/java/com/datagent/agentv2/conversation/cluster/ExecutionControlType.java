package com.datagent.agentv2.conversation.cluster;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 跨实例执行控制信号的有限类型。 */
public enum ExecutionControlType {
    CANCEL("cancel");

    private final String value;

    ExecutionControlType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ExecutionControlType fromValue(String value) {
        if (CANCEL.value.equals(value)) {
            return CANCEL;
        }
        throw new IllegalArgumentException("Unknown execution control type: " + value);
    }
}
