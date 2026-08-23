package com.datagent.agentv2.conversation.enums;

/** Agent 执行终止时可持久化的稳定失败代码。 */
public enum ExecutionFailureCode {
    DEADLINE_EXCEEDED("EXECUTION_DEADLINE_EXCEEDED"),
    CONTEXT_COMPACTION_FAILED("CONTEXT_COMPACTION_FAILED"),
    CONTEXT_WINDOW_EXCEEDED("CONTEXT_WINDOW_EXCEEDED"),
    INTERNAL_ERROR("EXECUTION_FAILED");

    private final String value;

    ExecutionFailureCode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
