package com.datagent.agentv2.conversation.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/** Agent 执行状态的有限业务取值。 */
public enum ExecutionStatus {
    QUEUED("queued"),
    RUNNING("running"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    DONE("done"),
    LOST("lost"),
    WAITING_FOR_USER("waiting_for_user"),
    WAITING_CONFIRMATION("waiting_confirmation");

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
                .orElseThrow(
                        () -> new IllegalArgumentException("Unknown execution status: " + value));
    }
}
