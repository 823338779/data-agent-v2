package com.datagent.agentv2.conversation.cluster;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/** 模型流增量的公开内容类型。 */
public enum ExecutionDeltaType {
    REASONING("reasoning_delta"),
    ASSISTANT("assistant_delta");

    private final String value;

    ExecutionDeltaType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ExecutionDeltaType fromValue(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.value.equals(value))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Unknown execution delta type: " + value));
    }
}
