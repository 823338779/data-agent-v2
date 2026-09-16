package com.datagent.agentv2.memory;

import java.time.Instant;

/** 固定用户的审查租约；fencingToken 只递增，释放租约也不会清零。 */
public record MemoryReviewLease(long fencingToken, String owner, Instant leaseUntil) {
    public MemoryReviewLease {
        if (fencingToken < 0) {
            throw new IllegalArgumentException("租约 fencing token 不得为负数");
        }
    }
}
