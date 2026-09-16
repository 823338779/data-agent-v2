package com.datagent.agentv2.memory;

/** 主对话写入原因；后台身份由调用上下文证明，不由该字段声明。 */
public enum MemoryWriteReason {
    USER_REQUESTED,
    AGENT_INFERRED
}
