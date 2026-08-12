package com.datagent.agentv2.context.lsm.domain;

import java.util.List;
import java.util.Objects;

/** 一次模型输入使用的有序摘要 frontier 及其覆盖与预算结果。 */
public record FrontierSelection(
        /** 按时间升序排列且父子互斥的摘要段标识。 */
        List<String> segmentIds,
        /** 与段标识一一对应的连续覆盖区间。 */
        List<LsmSummarySegment.Coverage> coverage,
        /** 所选摘要正文的 token 估算总数。 */
        int estimatedTokenCount) {

    public FrontierSelection {
        segmentIds = List.copyOf(Objects.requireNonNull(segmentIds, "segmentIds"));
        coverage = List.copyOf(Objects.requireNonNull(coverage, "coverage"));
        if (segmentIds.size() != coverage.size()) {
            throw new IllegalArgumentException("segmentIds and coverage must have the same size");
        }
        if (estimatedTokenCount < 0) {
            throw new IllegalArgumentException("estimatedTokenCount must not be negative");
        }
    }
}
