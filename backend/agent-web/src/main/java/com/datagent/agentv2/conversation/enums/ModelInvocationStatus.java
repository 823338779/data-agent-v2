package com.datagent.agentv2.conversation.enums;

/** 模型调用持久化生命周期状态。 */
@lombok.RequiredArgsConstructor
public enum ModelInvocationStatus {
    PREPARED("prepared"),
    STREAMING("streaming"),
    COMPLETED("completed");

    @lombok.Getter
    @lombok.experimental.Accessors(fluent = true)
    private final String value;

    public boolean matches(String candidate) {
        return value.equals(candidate);
    }
}
