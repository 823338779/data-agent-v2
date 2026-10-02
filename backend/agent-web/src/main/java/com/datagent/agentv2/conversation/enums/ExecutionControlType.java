package com.datagent.agentv2.conversation.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 跨实例执行控制信号的有限类型。 */
@lombok.RequiredArgsConstructor
public enum ExecutionControlType {
    CANCEL("cancel");

    @lombok.Getter(onMethod_ = @JsonValue)
    @lombok.experimental.Accessors(fluent = true)
    private final String value;

    @JsonCreator
    public static ExecutionControlType fromValue(String value) {
        if (CANCEL.value.equals(value)) {
            return CANCEL;
        }
        throw new IllegalArgumentException("Unknown execution control type: " + value);
    }
}
