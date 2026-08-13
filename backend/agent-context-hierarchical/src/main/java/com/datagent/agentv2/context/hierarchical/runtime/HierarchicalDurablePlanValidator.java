package com.datagent.agentv2.context.hierarchical.runtime;

import com.datagent.agentv2.context.hierarchical.domain.FrontierSelection;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionPlan;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionPlan.TurnRange;
import com.datagent.agentv2.context.hierarchical.domain.SummaryRange;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction.GenerationSettings;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction.PlannedCandidate;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GenerationRange;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.SourceKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 在 plan decode 和 generation 入口共享校验完整层级范围、descriptor 与生成身份绑定。 */
final class HierarchicalDurablePlanValidator {

    static final String INVALID_DURABLE_PLAN = "Invalid hierarchical durable plan invariants";
    private static final Comparator<SummaryRange> RANGE_ORDER =
            Comparator.comparingLong(SummaryRange::startTurn)
                    .thenComparingLong(SummaryRange::endTurn)
                    .thenComparingInt(SummaryRange::level);

    /** 返回去重且按持久化顺序排列的 required descriptors，失败时不允许进入 READY。 */
    List<PlannedCandidate> validate(
            GenerationSettings generationSettings,
            HierarchicalCompactionPlan plan,
            List<PlannedCandidate> candidates,
            PlannedCandidate coldCandidate) {
        GenerationSettings settings =
                Objects.requireNonNull(generationSettings, "generationSettings");
        HierarchicalCompactionPlan checkedPlan = Objects.requireNonNull(plan, "plan");
        List<PlannedCandidate> checkedCandidates =
                List.copyOf(Objects.requireNonNull(candidates, "candidates"));

        // descriptor ID、范围和 frozen generation identity 都必须一一对应。
        Map<String, PlannedCandidate> byId = new LinkedHashMap<>();
        Map<GenerationRange, PlannedCandidate> byRange = new LinkedHashMap<>();
        for (PlannedCandidate candidate : checkedCandidates) {
            PlannedCandidate checked = Objects.requireNonNull(candidate, "candidate");
            if (checked.sourceKind() == SourceKind.COLD
                    || !settings.matches(checked)
                    || byId.put(checked.nodeId(), checked) != null
                    || byRange.put(checked.range(), checked) != null) {
                throw invalid();
            }
        }

        // Cold/Warm/Hot 必须按完成轮次形成无缺口、无重叠的声明边界。
        validateTierLayout(checkedPlan);
        validateCold(checkedPlan, settings, coldCandidate, byRange);
        validateWarm(checkedPlan, byId, byRange);
        validateHot(checkedPlan, byRange);
        return List.copyOf(byId.values());
    }

    private static void validateTierLayout(HierarchicalCompactionPlan plan) {
        long cursor = 1L;
        for (TurnRange target : List.of(plan.coldTarget(), plan.warmTarget(), plan.hotTarget())) {
            if (target.isEmpty()) {
                continue;
            }
            if (target.startTurn() != cursor) {
                throw invalid();
            }
            cursor = target.endTurn() + 1L;
        }
        if (cursor != plan.completedTurnBoundary() + 1L) {
            throw invalid();
        }
    }

    private static void validateCold(
            HierarchicalCompactionPlan plan,
            GenerationSettings settings,
            PlannedCandidate coldCandidate,
            Map<GenerationRange, PlannedCandidate> byRange) {
        List<SummaryRange> promotions = plan.coldPromotionRanges();
        if (plan.coldTarget().isEmpty()) {
            if (!promotions.isEmpty() || coldCandidate != null) {
                throw invalid();
            }
            return;
        }
        if (promotions.isEmpty()) {
            if (coldCandidate != null) {
                throw invalid();
            }
            return;
        }
        validateContiguousSuffix(promotions, plan.coldTarget());
        promotions.forEach(range -> requireDescriptor(byRange, range));
        if (coldCandidate == null
                || coldCandidate.sourceKind() != SourceKind.COLD
                || !settings.matches(coldCandidate)
                || coldCandidate.range().startTurn() != plan.coldTarget().startTurn()
                || coldCandidate.range().endTurn() != plan.coldTarget().endTurn()
                || coldCandidate.range().level() != 0) {
            throw invalid();
        }
    }

    private static void validateWarm(
            HierarchicalCompactionPlan plan,
            Map<String, PlannedCandidate> byId,
            Map<GenerationRange, PlannedCandidate> byRange) {
        List<SummaryRange> targetRanges = plan.warmTargetRanges();
        FrontierSelection frontier = plan.frontier();
        if (plan.warmTarget().isEmpty()) {
            if (!targetRanges.isEmpty()
                    || !frontier.nodeIds().isEmpty()
                    || !frontier.ranges().isEmpty()
                    || !frontier.missingRanges().isEmpty()) {
                throw invalid();
            }
            return;
        }

        validateExactCover(targetRanges, plan.warmTarget());
        targetRanges.forEach(range -> requireDescriptor(byRange, range));

        // 已选择的正式 frontier 一旦存在，就必须自身完整覆盖 Warm target。
        if (!frontier.ranges().isEmpty()) {
            validateExactCover(frontier.ranges(), plan.warmTarget());
        } else if (frontier.publishable()) {
            throw invalid();
        }
        for (int index = 0; index < frontier.nodeIds().size(); index++) {
            PlannedCandidate descriptor = byId.get(frontier.nodeIds().get(index));
            if (descriptor == null
                    || !descriptor.range().equals(generationRange(frontier.ranges().get(index)))) {
                throw invalid();
            }
        }

        // missing parents 可与当前 frontier 重叠，但彼此不得重叠且必须位于 Warm 内。
        validateContainedNonOverlapping(frontier.missingRanges(), plan.warmTarget(), false);
        frontier.missingRanges().forEach(range -> requireDescriptor(byRange, range));
    }

    private static void validateHot(
            HierarchicalCompactionPlan plan, Map<GenerationRange, PlannedCandidate> byRange) {
        List<SummaryRange> emergency = plan.hotDegradationRanges();
        if (emergency.isEmpty()) {
            return;
        }
        if (plan.hotTarget().isEmpty()) {
            throw invalid();
        }
        validateExactCover(emergency, plan.hotTarget());
        for (SummaryRange range : emergency) {
            if (range.level() != 0) {
                throw invalid();
            }
            requireDescriptor(byRange, range);
        }
    }

    private static void validateExactCover(List<SummaryRange> ranges, TurnRange target) {
        if (ranges.isEmpty()) {
            throw invalid();
        }
        List<SummaryRange> ordered = ordered(ranges);
        long turnCursor = target.startTurn();
        long previousMessageEnd = 0L;
        for (SummaryRange range : ordered) {
            if (range.startTurn() != turnCursor
                    || range.startTurn() < target.startTurn()
                    || range.endTurn() > target.endTurn()
                    || (previousMessageEnd != 0L
                            && range.startMessageSequence() != previousMessageEnd + 1L)) {
                throw invalid();
            }
            turnCursor = range.endTurn() + 1L;
            previousMessageEnd = range.endMessageSequence();
        }
        if (turnCursor != target.endTurn() + 1L) {
            throw invalid();
        }
    }

    private static void validateContiguousSuffix(List<SummaryRange> ranges, TurnRange target) {
        List<SummaryRange> ordered = ordered(ranges);
        validateContainedNonOverlapping(ordered, target, true);
        if (ordered.getLast().endTurn() != target.endTurn()) {
            throw invalid();
        }
    }

    private static void validateContainedNonOverlapping(
            List<SummaryRange> ranges, TurnRange target, boolean contiguous) {
        long previousTurnEnd = 0L;
        long previousMessageEnd = 0L;
        for (SummaryRange range : ordered(ranges)) {
            if (range.startTurn() < target.startTurn()
                    || range.endTurn() > target.endTurn()
                    || (previousTurnEnd != 0L && range.startTurn() <= previousTurnEnd)
                    || (previousMessageEnd != 0L
                            && range.startMessageSequence() <= previousMessageEnd)
                    || (contiguous
                            && previousTurnEnd != 0L
                            && (range.startTurn() != previousTurnEnd + 1L
                                    || range.startMessageSequence() != previousMessageEnd + 1L))) {
                throw invalid();
            }
            previousTurnEnd = range.endTurn();
            previousMessageEnd = range.endMessageSequence();
        }
    }

    private static List<SummaryRange> ordered(List<SummaryRange> ranges) {
        List<SummaryRange> ordered = new ArrayList<>(ranges);
        ordered.sort(RANGE_ORDER);
        return List.copyOf(ordered);
    }

    private static void requireDescriptor(
            Map<GenerationRange, PlannedCandidate> byRange, SummaryRange range) {
        if (!byRange.containsKey(generationRange(range))) {
            throw invalid();
        }
    }

    private static GenerationRange generationRange(SummaryRange range) {
        return new GenerationRange(
                range.startTurn(),
                range.endTurn(),
                range.startMessageSequence(),
                range.endMessageSequence(),
                range.level());
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID_DURABLE_PLAN);
    }
}
