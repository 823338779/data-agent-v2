package com.datagent.agentv2.conversation.domain;

/** 会话聚合共享的身份与持久化水位约束，仅供领域内部使用。 */
final class DomainInvariants {
    private DomainInvariants() {}

    static String text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static long nonnegative(Long value, String name) {
        long normalized = value == null ? 0L : value;
        if (normalized < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return normalized;
    }

    static long advancedVersion(Long current, long next) {
        if (next < nonnegative(current, "version")) {
            throw new IllegalArgumentException("Persisted version must not move backwards");
        }
        return next;
    }
}
