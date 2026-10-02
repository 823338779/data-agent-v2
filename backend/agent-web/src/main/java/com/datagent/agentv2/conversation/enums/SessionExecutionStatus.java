package com.datagent.agentv2.conversation.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/** 会话列表展示使用的最新执行状态；尚无执行时使用 idle。 */
@lombok.RequiredArgsConstructor
public enum SessionExecutionStatus {
    IDLE("idle"),
    RUNNING("running"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    DONE("done");

    @lombok.Getter(onMethod_ = @JsonValue)
    @lombok.experimental.Accessors(fluent = true)
    private final String value;

    public static SessionExecutionStatus fromExecutionStatus(ExecutionStatus status) {
        return valueOf(status.name());
    }
}
