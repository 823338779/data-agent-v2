package com.datagent.agentv2.memory;

import java.time.Instant;
import java.util.Objects;

/** 后台审查领取凭证；租约及 fencing 字段由持久化层在数据库时钟下验证。 */
public record MemoryReviewClaim(
        String jobId,
        String conversationId,
        long epoch,
        long jobToken,
        long userToken,
        String owner,
        long claimedGeneration,
        long fromSequence,
        long throughSequence,
        Instant leaseUntil) {
    public MemoryReviewClaim {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (epoch < 1
                || jobToken < 1
                || userToken < 1
                || claimedGeneration < 1
                || fromSequence < 0
                || throughSequence < fromSequence) {
            throw new IllegalArgumentException("后台审查领取参数不得越界");
        }
    }
}
