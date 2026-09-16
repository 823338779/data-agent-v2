package com.datagent.agentv2.memory;

import java.time.LocalDateTime;
import java.util.Objects;

/** 已提交记忆操作的最小幂等记录，不复制请求正文。 */
public record MemoryOperation(
        String requestId, String payloadHash, MemoryOutcome outcome, LocalDateTime createdAt) {
    public MemoryOperation {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(payloadHash, "payloadHash");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
