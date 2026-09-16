package com.datagent.agentv2.memory;

/** 记忆首次写入的可信来源；后续确认或保护不会改写原始来源。 */
public enum MemorySource {
    USER_UI,
    USER_DIRECT,
    LLM_ACTIVE,
    BACKGROUND_REVIEW
}
