package com.datagent.agentv2.conversation.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/** Agent 执行生命周期事件的有限协议取值。 */
public enum ExecutionEventType {
    STARTED("execution_started"),
    DONE("execution_done"),
    COMPLETED("execution_completed"),
    FAILED("execution_failed"),
    CANCELLED("execution_cancelled"),
    PHASE_CHANGED("execution_phase_changed"),
    CONTEXT_COMPACTION_STARTED("context_compaction_started"),
    CONTEXT_COMPACTION_COMPLETED("context_compaction_completed"),
    CONTEXT_COMPACTION_FAILED("context_compaction_failed");

    private final String value;

    ExecutionEventType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ExecutionEventType fromValue(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown execution event type: " + value));
    }
}
