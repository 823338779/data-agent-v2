package com.datagent.agentv2.memory;

import java.time.LocalDateTime;
import java.util.Objects;

/** 已提交记忆操作的最小幂等记录，不复制请求正文。 */
public record MemoryOperation(
        String requestId,
        String payloadHash,
        MemoryOutcome outcome,
        LocalDateTime createdAt,
        /** 设置操作提交时的完整结果；普通正文操作为空。 */
        MemoryState settingsResult) {
    /** 普通记忆操作没有设置结果，保留既有构造入口。 */
    public MemoryOperation(
            String requestId, String payloadHash, MemoryOutcome outcome, LocalDateTime createdAt) {
        this(requestId, payloadHash, outcome, createdAt, null);
    }

    public MemoryOperation {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(payloadHash, "payloadHash");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
