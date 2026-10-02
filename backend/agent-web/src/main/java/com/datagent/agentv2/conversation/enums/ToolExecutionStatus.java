package com.datagent.agentv2.conversation.enums;

/** 工具调用持久化生命周期状态。 */
@lombok.RequiredArgsConstructor
public enum ToolExecutionStatus {
    PREPARED("prepared"),
    EXECUTING("executing"),
    SUCCEEDED("succeeded"),
    FAILED("failed");

    @lombok.Getter
    @lombok.experimental.Accessors(fluent = true)
    private final String value;

    public boolean matches(String candidate) {
        return value.equals(candidate);
    }
}
