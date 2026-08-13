package com.datagent.agentv2.context.hierarchical.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 固定历史边界后产生的 Hot、Warm、Cold 确定性规划结果。 */
public record HierarchicalCompactionPlan(
        /** 本计划可见的最后一个完整问答序号。 */
        long completedTurnBoundary,
        /** 当前未完成问答序号；没有进行中的问答时为空。 */
        Optional<Long> currentIncompleteTurn,
        /** 应由滚动摘要覆盖的目标 Cold 区间。 */
        TurnRange coldTarget,
        /** 应由二叉摘要树覆盖的目标 Warm 区间。 */
        TurnRange warmTarget,
        /** 正常情况下保留原文的目标 Hot 区间。 */
        TurnRange hotTarget,
        /** Cold 指针之后、目标 Cold 末尾之前的待迁移范围。 */
        List<SummaryRange> coldPromotionRanges,
        /** 完整铺满目标 Warm 区间的最大对齐二进制范围。 */
        List<SummaryRange> warmTargetRanges,
        /** 已选择的正式 Warm frontier；节点不足时包含缺失范围。 */
        FrontierSelection frontier,
        /** 仅硬上限被突破时按最老优先排列的 Hot 降级候选。 */
        List<SummaryRange> hotDegradationRanges) {

    /** 固化边界和范围副本，防止压缩执行期间发生漂移。 */
    public HierarchicalCompactionPlan {
        if (completedTurnBoundary < 0L) {
            throw new IllegalArgumentException("completedTurnBoundary must not be negative");
        }
        currentIncompleteTurn =
                Objects.requireNonNull(currentIncompleteTurn, "currentIncompleteTurn");
        coldTarget = Objects.requireNonNull(coldTarget, "coldTarget");
        warmTarget = Objects.requireNonNull(warmTarget, "warmTarget");
        hotTarget = Objects.requireNonNull(hotTarget, "hotTarget");
        coldPromotionRanges = List.copyOf(coldPromotionRanges);
        warmTargetRanges = List.copyOf(warmTargetRanges);
        frontier = Objects.requireNonNull(frontier, "frontier");
        hotDegradationRanges = List.copyOf(hotDegradationRanges);
    }

    /** 可为空的连续问答层级边界；空范围统一表示为 0..0。 */
    public record TurnRange(long startTurn, long endTurn) {

        public static final TurnRange EMPTY = new TurnRange(0L, 0L);

        /** 拒绝半空或倒退边界。 */
        public TurnRange {
            boolean empty = startTurn == 0L && endTurn == 0L;
            if (!empty && (startTurn <= 0L || endTurn < startTurn)) {
                throw new IllegalArgumentException("Invalid turn range");
            }
        }

        /** 返回层级区间是否为空。 */
        public boolean isEmpty() {
            return startTurn == 0L;
        }

        /** 返回区间内完整问答数量。 */
        public long coveredTurns() {
            return isEmpty() ? 0L : endTurn - startTurn + 1L;
        }
    }
}
