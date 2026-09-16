package com.datagent.agentv2.memory.review;

import com.datagent.agentv2.memory.MemoryEvidence;

/** 原始序号扫描结果；排除项可推进，未终态项必须保留连续边界。 */
public record MemoryReviewMessage(long sequence, State state, MemoryEvidence evidence) {
    public enum State {
        ELIGIBLE,
        EXCLUDED,
        PENDING
    }
}
