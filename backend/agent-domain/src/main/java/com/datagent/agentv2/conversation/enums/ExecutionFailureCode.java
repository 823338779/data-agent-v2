package com.datagent.agentv2.conversation.enums;

/** Agent 执行终止时可持久化的稳定失败分类。 */
@lombok.RequiredArgsConstructor
public enum ExecutionFailureCode {
    DEADLINE_EXCEEDED("EXECUTION_DEADLINE_EXCEEDED"),
    CONTEXT_COMPACTION_FAILED("CONTEXT_COMPACTION_FAILED"),
    CONTEXT_WINDOW_EXCEEDED("CONTEXT_WINDOW_EXCEEDED"),
    PROCESS_RESTARTED("PROCESS_RESTARTED"),
    INTERNAL_ERROR("EXECUTION_FAILED");

    @lombok.Getter(onMethod_ = @JsonValue)
    @lombok.experimental.Accessors(fluent = true)
    private final String value;

    public String value() {
        return value;
    }

    public static ExecutionFailureCode fromValue(String value) {
        for (ExecutionFailureCode candidate : values()) {
            if (candidate.value.equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown execution failure code: " + value);
    }
}
