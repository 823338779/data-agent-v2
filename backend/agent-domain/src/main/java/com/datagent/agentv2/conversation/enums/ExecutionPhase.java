package com.datagent.agentv2.conversation.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/** Agent 执行进度阶段的有限业务取值。 */
public enum ExecutionPhase {
    RECEIVED,
    PREPARING_CONTEXT,
    CONNECTING_MODEL,
    ANSWERING,
    PERSISTING,
    COMPLETED;

    @JsonValue
    public String value() {
        return name();
    }

    @JsonCreator
    public static ExecutionPhase fromValue(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.name().equals(value))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("Unknown execution phase: " + value));
    }
}
