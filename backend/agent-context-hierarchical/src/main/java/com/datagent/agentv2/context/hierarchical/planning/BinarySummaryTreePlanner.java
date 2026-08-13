package com.datagent.agentv2.context.hierarchical.planning;

import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.context.hierarchical.domain.FrontierSelection;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionPlan;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionPlan.TurnRange;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.domain.SummaryRange;
import com.datagent.agentv2.conversation.context.ContextBudget;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 依据完整问答边界生成对齐的 Cold、Warm、Hot 二进制规划。 */
public final class BinarySummaryTreePlanner {

    private static final Comparator<ConversationTurn> TURN_ORDER =
            Comparator.comparingLong(ConversationTurn::ordinal);

    private final HierarchicalContextPolicy policy;

    /** 使用 execution 首次固化的策略窗口规划所有层级边界。 */
    public BinarySummaryTreePlanner(HierarchicalContextPolicySnapshot snapshot) {
        this.policy = new HierarchicalContextPolicy(snapshot);
    }

    /** 固定当前历史边界，并规划 Cold 迁移与 Warm 对齐范围。 */
    public HierarchicalCompactionPlan plan(
            List<ConversationTurn> turns, long summarizedCompletedTurnCount) {
        if (summarizedCompletedTurnCount < 0L) {
            throw new IllegalArgumentException("summarizedCompletedTurnCount must not be negative");
        }
        List<ConversationTurn> orderedTurns = validatedTurns(turns);
        List<ConversationTurn> completedTurns =
                orderedTurns.stream().filter(ConversationTurn::completed).toList();
        long completedBoundary = completedTurns.size();
        if (summarizedCompletedTurnCount > completedBoundary) {
            throw new IllegalArgumentException("Cold pointer exceeds completed history");
        }

        // 完成轮数决定三个连续逻辑层级；当前未完成轮不参与任何计数。
        TurnRange hotTarget =
                completedBoundary == 0L
                        ? TurnRange.EMPTY
                        : range(
                                Math.max(1L, completedBoundary - policy.recentRawTurns() + 1L),
                                completedBoundary);
        TurnRange warmTarget =
                hotTarget.isEmpty() || hotTarget.startTurn() == 1L
                        ? TurnRange.EMPTY
                        : range(
                                Math.max(1L, completedBoundary - policy.warmWindowTurns() + 1L),
                                hotTarget.startTurn() - 1L);
        TurnRange coldTarget =
                warmTarget.isEmpty() || warmTarget.startTurn() == 1L
                        ? TurnRange.EMPTY
                        : range(1L, warmTarget.startTurn() - 1L);

        // 任意连续边界都分解为最大优先的对齐 2^n 范围，保证没有缺口或重叠。
        long promotionStart = summarizedCompletedTurnCount + 1L;
        List<SummaryRange> coldPromotions =
                coldTarget.isEmpty() || promotionStart > coldTarget.endTurn()
                        ? List.of()
                        : alignedRanges(completedTurns, promotionStart, coldTarget.endTurn());
        List<SummaryRange> warmRanges =
                warmTarget.isEmpty()
                        ? List.of()
                        : alignedRanges(
                                completedTurns, warmTarget.startTurn(), warmTarget.endTurn());

        Optional<Long> incompleteTurn =
                orderedTurns.stream()
                        .filter(turn -> !turn.completed())
                        .map(ConversationTurn::ordinal)
                        .findFirst();
        return new HierarchicalCompactionPlan(
                completedBoundary,
                incompleteTurn,
                coldTarget,
                warmTarget,
                hotTarget,
                coldPromotions,
                warmRanges,
                FrontierSelection.empty(),
                List.of());
    }

    /** 在固定历史规划上追加仅由硬上限突破触发的 Hot 原子降级候选。 */
    public HierarchicalCompactionPlan plan(
            List<ConversationTurn> turns,
            long summarizedCompletedTurnCount,
            int estimatedInputTokens,
            ContextBudget budget) {
        HierarchicalCompactionPlan base = plan(turns, summarizedCompletedTurnCount);
        if (!policy.requiresHotDegradation(estimatedInputTokens, budget)
                || base.hotTarget().isEmpty()) {
            return base;
        }

        // Hot 候选携带真实消息边界，按最老问答优先逐轮声明且绝不含当前未完成轮。
        List<ConversationTurn> completedTurns =
                validatedTurns(turns).stream().filter(ConversationTurn::completed).toList();
        List<SummaryRange> hotCandidates =
                leafRanges(
                        completedTurns, base.hotTarget().startTurn(), base.hotTarget().endTurn());
        return new HierarchicalCompactionPlan(
                base.completedTurnBoundary(),
                base.currentIncompleteTurn(),
                base.coldTarget(),
                base.warmTarget(),
                base.hotTarget(),
                base.coldPromotionRanges(),
                base.warmTargetRanges(),
                base.frontier(),
                hotCandidates);
    }

    /** 由两个相邻同层子节点构造唯一合法父范围。 */
    public SummaryRange parent(SummaryRange left, SummaryRange right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (left.level() != right.level()
                || left.endTurn() + 1L != right.startTurn()
                || left.endMessageSequence() >= right.startMessageSequence()) {
            throw new IllegalArgumentException("Parent requires adjacent same-level children");
        }
        return new SummaryRange(
                left.startTurn(),
                right.endTurn(),
                left.startMessageSequence(),
                right.endMessageSequence(),
                left.level() + 1);
    }

    /** 返回给定 Warm 轮数能实际使用的最高二叉层级。 */
    public int maximumUsefulLevel(long warmTurns) {
        if (warmTurns < 0L) {
            throw new IllegalArgumentException("warmTurns must not be negative");
        }
        return warmTurns == 0L ? 0 : 63 - Long.numberOfLeadingZeros(warmTurns);
    }

    private static List<ConversationTurn> validatedTurns(List<ConversationTurn> turns) {
        List<ConversationTurn> ordered =
                Objects.requireNonNull(turns, "turns").stream()
                        .map(turn -> Objects.requireNonNull(turn, "turn"))
                        .sorted(TURN_ORDER)
                        .toList();
        boolean incompleteSeen = false;
        for (int index = 0; index < ordered.size(); index++) {
            ConversationTurn turn = ordered.get(index);
            if (turn.ordinal() != index + 1L || (incompleteSeen && turn.completed())) {
                throw new IllegalArgumentException(
                        "Turns must be continuous with incomplete turn last");
            }
            if (!turn.completed()) {
                if (incompleteSeen) {
                    throw new IllegalArgumentException("Only one incomplete turn is allowed");
                }
                incompleteSeen = true;
            }
        }
        return ordered;
    }

    private static List<SummaryRange> alignedRanges(
            List<ConversationTurn> completedTurns, long startTurn, long endTurn) {
        List<SummaryRange> ranges = new ArrayList<>();
        long cursor = startTurn;
        while (cursor <= endTurn) {
            long remaining = endTurn - cursor + 1L;
            int level = maximumAlignedLevel(cursor, remaining);
            long coveredTurns = 1L << level;
            ConversationTurn first = completedTurns.get(Math.toIntExact(cursor - 1L));
            ConversationTurn last = completedTurns.get(Math.toIntExact(cursor + coveredTurns - 2L));
            ranges.add(
                    new SummaryRange(
                            cursor,
                            cursor + coveredTurns - 1L,
                            first.startMessageSequence(),
                            last.endMessageSequence(),
                            level));
            cursor += coveredTurns;
        }
        return List.copyOf(ranges);
    }

    private static List<SummaryRange> leafRanges(
            List<ConversationTurn> completedTurns, long startTurn, long endTurn) {
        List<SummaryRange> ranges = new ArrayList<>();
        for (long turn = startTurn; turn <= endTurn; turn++) {
            ConversationTurn source = completedTurns.get(Math.toIntExact(turn - 1L));
            ranges.add(
                    new SummaryRange(
                            turn,
                            turn,
                            source.startMessageSequence(),
                            source.endMessageSequence(),
                            0));
        }
        return List.copyOf(ranges);
    }

    private static int maximumAlignedLevel(long startTurn, long remainingTurns) {
        int remainingLevel = 63 - Long.numberOfLeadingZeros(remainingTurns);
        long zeroBasedStart = startTurn - 1L;
        int alignmentLevel = zeroBasedStart == 0L ? 62 : Long.numberOfTrailingZeros(zeroBasedStart);
        return Math.min(remainingLevel, alignmentLevel);
    }

    private static TurnRange range(long startTurn, long endTurn) {
        return startTurn > endTurn ? TurnRange.EMPTY : new TurnRange(startTurn, endTurn);
    }
}
