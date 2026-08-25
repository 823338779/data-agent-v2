package com.datagent.agentv2.conversation.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/** Agent 执行持久化状态；枚举值同时约束领域状态机和对外协议。 */
public enum ExecutionStatus {
    RUNNING("running"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    DONE("done");

    private final String value;

    ExecutionStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ExecutionStatus fromValue(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown execution status: " + value));
    }
}
