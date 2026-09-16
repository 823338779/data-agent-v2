package com.datagent.agentv2.memory;

/** 记忆操作的稳定业务状态，供工具和 HTTP 边界统一解释。 */
public enum MemoryStatus {
    CREATED,
    ALREADY_EXISTS,
    UPDATED,
    REMOVED,
    CONFIRMED,
    INVALID_REQUEST,
    UNSAFE_CONTENT,
    CONFLICT,
    DUPLICATE_CONFLICT,
    NOT_FOUND,
    PROTECTED,
    SUPPRESSED,
    QUOTA_EXCEEDED,
    LEARNING_DISABLED,
    LEASE_LOST,
    UNAVAILABLE
}
