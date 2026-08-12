package com.datagent.agentv2.context.lsm.domain;

import java.util.List;
import java.util.Objects;

/** 规划层使用的不可变摘要段，只携带层级、覆盖和来源关系。 */
public record LsmSummarySegment(
        /** 摘要段的稳定业务标识，规划中的候选段也使用确定性标识。 */
        String segmentId,
        /** 区分普通 size-tiered 节点和配置顶层唯一 ROOT。 */
        Kind kind,
        /** 节点所在逻辑层，ROOT 的层等于生成它时的配置顶层。 */
        int level,
        /** 节点覆盖的连续完整问答区间。 */
        Coverage coverage,
        /** 摘要正文参与 Input Frontier 预算的 token 估算值。 */
        int estimatedTokenCount,
        /** 生成当前节点的直接来源段标识，顺序必须从旧到新。 */
        List<String> sourceSegmentIds,
        /** L0 是否由不足基础批次的完整问答强制封存。 */
        boolean partialSegment) {

    public LsmSummarySegment {
        segmentId = Objects.requireNonNull(segmentId, "segmentId");
        kind = Objects.requireNonNull(kind, "kind");
        coverage = Objects.requireNonNull(coverage, "coverage");
        sourceSegmentIds =
                List.copyOf(Objects.requireNonNull(sourceSegmentIds, "sourceSegmentIds"));
        if (segmentId.isBlank()) {
            throw new IllegalArgumentException("segmentId must not be blank");
        }
        if (level < 0) {
            throw new IllegalArgumentException("level must not be negative");
        }
        if (estimatedTokenCount < 0) {
            throw new IllegalArgumentException("estimatedTokenCount must not be negative");
        }
    }

    /** 返回覆盖区间的完整问答数量。 */
    public long turnCount() {
        return coverage.turnCount();
    }

    /** 摘要节点的持久化种类。 */
    public enum Kind {
        STANDARD,
        ROOT
    }

    /** 闭区间表示的连续完整问答覆盖。 */
    public record Coverage(
            /** 覆盖的首个完整问答序号。 */
            long startTurnSequence,
            /** 覆盖的最后一个完整问答序号。 */
            long endTurnSequence) {

        public Coverage {
            if (startTurnSequence <= 0L) {
                throw new IllegalArgumentException("startTurnSequence must be positive");
            }
            if (endTurnSequence < startTurnSequence) {
                throw new IllegalArgumentException("coverage end must not precede start");
            }
        }

        /** 返回闭区间内完整问答的数量。 */
        public long turnCount() {
            return endTurnSequence - startTurnSequence + 1L;
        }
    }
}
