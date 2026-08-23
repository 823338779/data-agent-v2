package com.datagent.agentv2.conversation.stream;

import java.time.Instant;
import java.util.Objects;

/** 当前进程内直接投递给 SSE 连接的回答增量。 */
public record LocalExecutionDelta(
        String tenantId,
        String conversationSessionId,
        String executionId,
        long modelInvocationSequence,
        long outputDeltaSequence,
        String delta,
        Instant occurredAt) {

    public LocalExecutionDelta {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(conversationSessionId, "conversationSessionId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (modelInvocationSequence < 1L || outputDeltaSequence < 1L) {
            throw new IllegalArgumentException("invalid local execution delta");
        }
    }
}
