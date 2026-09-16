package com.datagent.agentv2.memory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;

/** 可恢复的会话审查任务；游标范围使用 (reviewedThroughSequence, claimedThroughSequence]。 */
public record MemoryReviewJob(
        String jobId,
        String conversationId,
        long learningEpoch,
        long reviewedThroughSequence,
        long pendingThroughSequence,
        long claimedThroughSequence,
        long dirtyGeneration,
        long claimedGeneration,
        Status status,
        LocalDateTime availableAt,
        int failureCount,
        String failureReason,
        String owner,
        long fencingToken,
        Instant leaseUntil) {
    public enum Status {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        BLOCKED,
        CANCELLED
    }

    public MemoryReviewJob {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(availableAt, "availableAt");
        if (learningEpoch < 1
                || reviewedThroughSequence < 0
                || pendingThroughSequence < reviewedThroughSequence
                || claimedThroughSequence < reviewedThroughSequence
                || dirtyGeneration < 1
                || claimedGeneration < 0
                || failureCount < 0
                || fencingToken < 0) {
            throw new IllegalArgumentException("审查任务版本或游标不得越界");
        }
    }
}
