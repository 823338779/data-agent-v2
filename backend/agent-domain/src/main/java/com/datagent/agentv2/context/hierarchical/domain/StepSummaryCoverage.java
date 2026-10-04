package com.datagent.agentv2.context.hierarchical.domain;

import java.util.Objects;

/** V3 摘要的左开右闭覆盖及来源摘要，与 V2 的轮次范围完全独立。 */
public record StepSummaryCoverage(
        /** 连续前缀起点，滚动摘要为 ORIGIN。 */
        ContextStepCursor fromExclusive,
        /** 已覆盖的最后一个完整组。 */
        ContextStepCursor throughInclusive,
        /** 有序来源前缀的 SHA-256。 */
        String sourceDigest) {
    public StepSummaryCoverage {
        Objects.requireNonNull(fromExclusive, "fromExclusive");
        Objects.requireNonNull(throughInclusive, "throughInclusive");
        if (fromExclusive.compareTo(throughInclusive) >= 0
                || sourceDigest == null
                || sourceDigest.length() != 64) {
            throw new IllegalArgumentException("Invalid step summary coverage");
        }
    }
}
