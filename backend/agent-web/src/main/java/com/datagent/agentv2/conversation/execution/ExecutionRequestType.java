package com.datagent.agentv2.conversation.execution;

/** 受幂等键保护的 Agent 执行请求类型。 */
public enum ExecutionRequestType {
    MESSAGE_SUBMISSION("message_submission"),
    EXECUTION_RETRY("execution_retry");

    private final String value;

    ExecutionRequestType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
