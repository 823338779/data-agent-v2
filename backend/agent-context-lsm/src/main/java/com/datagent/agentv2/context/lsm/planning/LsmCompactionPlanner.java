package com.datagent.agentv2.context.lsm.planning;

import com.datagent.agentv2.context.lsm.domain.ConversationTurn;
import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan;
import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan.HotBoundary;
import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan.OperationType;
import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan.PlannedOperation;
import com.datagent.agentv2.context.lsm.domain.LsmContextManifest;
import com.datagent.agentv2.context.lsm.domain.LsmPlanningInput;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment.Coverage;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment.Kind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 按固定顺序规划 L0 封存、size-tiered 晋升、Warm 收敛和 Major Compaction。 */
public final class LsmCompactionPlanner {

    private static final int HOT_COMPLETED_TURNS = 10;
    private static final int BASE_SEGMENT_TURNS = 10;
    private static final int MIN_WARM_TURNS = 100;
    private static final int MIN_MAJOR_COLD_NODES = 3;
    private static final double NORMAL_SUMMARY_RATIO = 0.60d;
    private static final double MAJOR_SUMMARY_RATIO = 0.80d;
    private static final double MIN_MAJOR_REDUCTION_RATIO = 0.20d;
    private static final int ROOT_TOKEN_LIMIT = 4_096;
    private static final String PLANNED_ID_PREFIX = "planned-";
    private static final String ID_SEPARATOR = "-";

    private static final Comparator<LsmSummarySegment> CHRONOLOGICAL =
            Comparator.comparingLong(
                            (LsmSummarySegment segment) -> segment.coverage().startTurnSequence())
                    .thenComparingLong(segment -> segment.coverage().endTurnSequence())
                    .thenComparingInt(LsmSummarySegment::level)
                    .thenComparing(LsmSummarySegment::segmentId);

    private final ConversationTokenEstimator tokenEstimator = new ConversationTokenEstimator();
    private final LsmInvariantValidator validator = new LsmInvariantValidator();

    /** 对冻结输入执行唯一规划顺序，并返回成功发布后的确定性投影。 */
    public LsmCompactionPlan plan(LsmPlanningInput input) {
        Objects.requireNonNull(input, "input");
        HotBoundary hot = protectCurrentAndLastCompleted(input.turns());
        PlanningState state = initialState(input);

        // 规划顺序属于持久化恢复契约：先封 L0，再协调层级，最后移动边界和按压力合并 Cold。
        sealFullL0Batches(input, hot, state);
        reconcileLowerMaxLevel(input, state);
        promoteFullLevels(input.policy().fanout(), input.policy().maxLevel(), state);
        WarmRange warmRange = trimWarmToRange(input, hot, state);
        planMajorCompactionWhenUseful(input, warmRange, state);

        LsmContextManifest proposed = proposedManifest(input, hot, warmRange, state);
        LsmCompactionPlan plan =
                new LsmCompactionPlan(
                        hot,
                        state.operations,
                        proposed,
                        state.activeSegments.stream().sorted(CHRONOLOGICAL).toList());
        validator.validatePlan(input, plan);
        return plan;
    }

    private void sealFullL0Batches(LsmPlanningInput input, HotBoundary hot, PlanningState state) {
        Long eligibleEnd = hot.startTurnSequence() == null ? null : hot.startTurnSequence() - 1L;
        if (eligibleEnd == null || eligibleEnd <= 0L) {
            return;
        }
        long coveredEnd =
                state.activeSegments.stream()
                        .mapToLong(segment -> segment.coverage().endTurnSequence())
                        .max()
                        .orElse(0L);
        long nextStart = Math.max(coveredEnd + 1L, 1L);

        // 正常 L0 只能覆盖十个完整问答；每个问答内部的 tool 协议原子不会在这里被拆开。
        while (nextStart + BASE_SEGMENT_TURNS - 1L <= eligibleEnd) {
            long batchEnd = nextStart + BASE_SEGMENT_TURNS - 1L;
            addSeal(input, state, nextStart, batchEnd, false);
            nextStart = batchEnd + 1L;
        }

        // forced L0 仅在调用方确认硬压力且显式授权时封存剩余完整问答。
        if (nextStart <= eligibleEnd
                && input.forcePartialL0()
                && input.pressure().aboveHardLimit()) {
            addSeal(input, state, nextStart, eligibleEnd, true);
        }
    }

    private void addSeal(
            LsmPlanningInput input, PlanningState state, long start, long end, boolean partial) {
        Coverage coverage = new Coverage(start, end);
        int tokens = estimateTurnRange(input.turns(), coverage);
        String targetId = plannedId(OperationType.SEAL_L0, 0, coverage);
        PlannedOperation operation =
                new PlannedOperation(
                        OperationType.SEAL_L0,
                        targetId,
                        List.of(),
                        Kind.STANDARD,
                        0,
                        coverage,
                        partial,
                        tokens);
        state.operations.add(operation);
        state.activeSegments.add(
                new LsmSummarySegment(
                        targetId, Kind.STANDARD, 0, coverage, tokens, List.of(), partial));
        state.activeSegments.sort(CHRONOLOGICAL);
    }

    private static void reconcileLowerMaxLevel(LsmPlanningInput input, PlanningState state) {
        LsmSummarySegment root = activeRoot(state.activeSegments);
        if (root == null || root.level() <= input.policy().maxLevel()) {
            return;
        }
        List<LsmSummarySegment> tail =
                state.activeSegments.stream()
                        .filter(segment -> segment.kind() == Kind.STANDARD)
                        .filter(
                                segment ->
                                        segment.coverage().startTurnSequence()
                                                > root.coverage().endTurnSequence())
                        .sorted(CHRONOLOGICAL)
                        .toList();
        if (tail.size() < input.policy().fanout()) {
            return;
        }

        // maxLevel 降低不能展开旧 ROOT，只能把旧前缀与其后连续 Cold Tail 协调成新顶层 ROOT。
        List<LsmSummarySegment> sources = new ArrayList<>();
        sources.add(root);
        sources.addAll(tail.subList(0, input.policy().fanout()));
        mergeSources(
                state,
                sources,
                OperationType.RECONCILE_CONFIGURATION,
                Kind.ROOT,
                input.policy().maxLevel(),
                NORMAL_SUMMARY_RATIO);
    }

    private static void promoteFullLevels(int fanout, int maxLevel, PlanningState state) {
        for (int level = 0; level < maxLevel; level++) {
            while (true) {
                List<LsmSummarySegment> batch =
                        firstAdjacentBatch(state.activeSegments, level, fanout);
                if (batch.isEmpty()) {
                    break;
                }
                int targetLevel = level + 1;
                if (targetLevel == maxLevel) {
                    LsmSummarySegment root = activeRoot(state.activeSegments);
                    List<LsmSummarySegment> sources = new ArrayList<>();
                    if (root != null) {
                        if (root.coverage().endTurnSequence() + 1L
                                != batch.getFirst().coverage().startTurnSequence()) {
                            break;
                        }
                        sources.add(root);
                    }
                    sources.addAll(batch);
                    mergeSources(
                            state,
                            sources,
                            OperationType.ROLL_ROOT,
                            Kind.ROOT,
                            targetLevel,
                            NORMAL_SUMMARY_RATIO);
                } else {
                    mergeSources(
                            state,
                            batch,
                            OperationType.PROMOTE,
                            Kind.STANDARD,
                            targetLevel,
                            NORMAL_SUMMARY_RATIO);
                }
            }
        }
    }

    private static WarmRange trimWarmToRange(
            LsmPlanningInput input, HotBoundary hot, PlanningState state) {
        if (hot.startTurnSequence() == null || hot.startTurnSequence() <= 1L) {
            return WarmRange.empty();
        }
        long warmEnd = hot.startTurnSequence() - 1L;
        long availableWarmTurns = warmEnd;
        long warmCount =
                availableWarmTurns < MIN_WARM_TURNS
                        ? availableWarmTurns
                        : MIN_WARM_TURNS
                                + (availableWarmTurns - MIN_WARM_TURNS) % BASE_SEGMENT_TURNS;
        long warmStart = warmEnd - warmCount + 1L;

        // 发布时把超出 109 的旧 Warm 以十轮批次迁出；候选边界最终稳定在 100～109 轮。
        Long priorWarmStart = input.manifest().warmStartTurnSequence();
        long movedStart = priorWarmStart == null ? 1L : priorWarmStart;
        if (warmStart > movedStart) {
            Coverage movedCoverage = new Coverage(movedStart, warmStart - 1L);
            state.operations.add(
                    new PlannedOperation(
                            OperationType.TRIM_WARM,
                            null,
                            List.of(),
                            Kind.STANDARD,
                            0,
                            movedCoverage,
                            false,
                            0));
        }
        return new WarmRange(warmStart, warmEnd);
    }

    private static void planMajorCompactionWhenUseful(
            LsmPlanningInput input, WarmRange warmRange, PlanningState state) {
        if (!input.pressure().aboveTarget()) {
            return;
        }
        long coldEnd = warmRange.present() ? warmRange.start - 1L : Long.MAX_VALUE;
        List<LsmSummarySegment> coldNodes =
                state.activeSegments.stream()
                        .filter(segment -> segment.kind() == Kind.STANDARD)
                        .filter(segment -> segment.coverage().endTurnSequence() <= coldEnd)
                        .sorted(CHRONOLOGICAL)
                        .toList();
        if (coldNodes.size() < MIN_MAJOR_COLD_NODES) {
            return;
        }

        LsmSummarySegment root = activeRoot(state.activeSegments);
        List<LsmSummarySegment> sources = new ArrayList<>();
        if (root != null) {
            sources.add(root);
        }
        sources.addAll(coldNodes);
        sources.sort(CHRONOLOGICAL);
        if (!continuous(sources)) {
            return;
        }
        long sourceTokens =
                sources.stream().mapToLong(LsmSummarySegment::estimatedTokenCount).sum();
        int projectedTokens = projectedTokens(sources, Kind.ROOT, MAJOR_SUMMARY_RATIO);
        double reduction =
                sourceTokens == 0L
                        ? 0.0d
                        : (sourceTokens - projectedTokens) / (double) sourceTokens;
        if (reduction + Math.ulp(reduction) < MIN_MAJOR_REDUCTION_RATIO) {
            return;
        }
        mergeSources(
                state,
                sources,
                OperationType.MAJOR_COMPACTION,
                Kind.ROOT,
                input.policy().maxLevel(),
                MAJOR_SUMMARY_RATIO);
    }

    private static LsmContextManifest proposedManifest(
            LsmPlanningInput input, HotBoundary hot, WarmRange warmRange, PlanningState state) {
        List<LsmSummarySegment> ordered =
                state.activeSegments.stream().sorted(CHRONOLOGICAL).toList();
        LsmSummarySegment root = activeRoot(ordered);

        // 尚未封存且位于 Hot 之前的完整问答继续作为 pending raw，由后续硬压力计划处理。
        long coveredEnd =
                ordered.stream()
                        .mapToLong(segment -> segment.coverage().endTurnSequence())
                        .max()
                        .orElse(0L);
        Long eligibleEnd = hot.startTurnSequence() == null ? null : hot.startTurnSequence() - 1L;
        Long pendingStart = null;
        Long pendingEnd = null;
        if (eligibleEnd != null && coveredEnd < eligibleEnd) {
            pendingStart = coveredEnd + 1L;
            pendingEnd = eligibleEnd;
        }
        return new LsmContextManifest(
                root == null ? null : root.segmentId(),
                ordered.stream().map(LsmSummarySegment::segmentId).toList(),
                pendingStart,
                pendingEnd,
                warmRange.present() ? warmRange.start : null,
                warmRange.present() ? warmRange.end : null,
                input.manifest().generation() + 1L,
                input.policy().fanout(),
                input.policy().maxLevel());
    }

    private static PlanningState initialState(LsmPlanningInput input) {
        Map<String, LsmSummarySegment> byId = new HashMap<>();
        for (LsmSummarySegment segment : input.segments()) {
            if (byId.put(segment.segmentId(), segment) != null) {
                throw new IllegalArgumentException("duplicate segmentId: " + segment.segmentId());
            }
        }
        List<LsmSummarySegment> active = new ArrayList<>();
        Set<String> activeIds = new LinkedHashSet<>(input.manifest().activeSegmentIds());
        if (activeIds.size() != input.manifest().activeSegmentIds().size()) {
            throw new IllegalArgumentException("manifest contains duplicate active segment IDs");
        }
        for (String activeId : activeIds) {
            LsmSummarySegment segment = byId.get(activeId);
            if (segment == null) {
                throw new IllegalArgumentException(
                        "manifest references missing segment: " + activeId);
            }
            active.add(segment);
        }
        active.sort(CHRONOLOGICAL);
        if (!active.isEmpty() && !continuous(active)) {
            throw new IllegalArgumentException("active Storage Frontier must be continuous");
        }
        return new PlanningState(active);
    }

    private static HotBoundary protectCurrentAndLastCompleted(List<ConversationTurn> turns) {
        List<ConversationTurn> completed =
                turns.stream().filter(ConversationTurn::completed).toList();
        int hotCount = Math.min(HOT_COMPLETED_TURNS, completed.size());
        Long hotStart =
                hotCount == 0 ? null : completed.get(completed.size() - hotCount).sequence();
        Long hotEnd = hotCount == 0 ? null : completed.getLast().sequence();
        Long current =
                turns.stream()
                        .filter(turn -> !turn.completed())
                        .map(ConversationTurn::sequence)
                        .reduce((first, second) -> second)
                        .orElse(null);
        return new HotBoundary(hotStart, hotEnd, hotCount, current);
    }

    private int estimateTurnRange(List<ConversationTurn> turns, Coverage coverage) {
        return turns.stream()
                .filter(turn -> turn.sequence() >= coverage.startTurnSequence())
                .filter(turn -> turn.sequence() <= coverage.endTurnSequence())
                .mapToInt(turn -> tokenEstimator.estimateMessages(turn.messages()))
                .reduce(0, LsmCompactionPlanner::saturatedAdd);
    }

    private static List<LsmSummarySegment> firstAdjacentBatch(
            List<LsmSummarySegment> activeSegments, int level, int fanout) {
        List<LsmSummarySegment> candidates =
                activeSegments.stream()
                        .filter(segment -> segment.kind() == Kind.STANDARD)
                        .filter(segment -> segment.level() == level)
                        .sorted(CHRONOLOGICAL)
                        .toList();
        for (int start = 0; start + fanout <= candidates.size(); start++) {
            List<LsmSummarySegment> batch = candidates.subList(start, start + fanout);
            if (continuous(batch)) {
                return List.copyOf(batch);
            }
        }
        return List.of();
    }

    private static void mergeSources(
            PlanningState state,
            List<LsmSummarySegment> sources,
            OperationType type,
            Kind targetKind,
            int targetLevel,
            double summaryRatio) {
        if (sources.isEmpty() || !continuous(sources)) {
            throw new IllegalArgumentException("merge sources must be non-empty and continuous");
        }
        Coverage coverage =
                new Coverage(
                        sources.getFirst().coverage().startTurnSequence(),
                        sources.getLast().coverage().endTurnSequence());
        String targetId = plannedId(type, targetLevel, coverage);
        int tokens = projectedTokens(sources, targetKind, summaryRatio);
        List<String> sourceIds = sources.stream().map(LsmSummarySegment::segmentId).toList();
        PlannedOperation operation =
                new PlannedOperation(
                        type,
                        targetId,
                        sourceIds,
                        targetKind,
                        targetLevel,
                        coverage,
                        false,
                        tokens);

        // 每个候选父节点只在前置来源完成后生成，投影立即替换来源以支持同计划内级联晋升。
        state.operations.add(operation);
        Set<String> removedIds = Set.copyOf(sourceIds);
        state.activeSegments.removeIf(segment -> removedIds.contains(segment.segmentId()));
        state.activeSegments.add(
                new LsmSummarySegment(
                        targetId, targetKind, targetLevel, coverage, tokens, sourceIds, false));
        state.activeSegments.sort(CHRONOLOGICAL);
    }

    private static int projectedTokens(
            List<LsmSummarySegment> sources, Kind targetKind, double ratio) {
        long sourceTokens =
                sources.stream().mapToLong(LsmSummarySegment::estimatedTokenCount).sum();
        long projected = (long) Math.ceil(sourceTokens * ratio);
        if (targetKind == Kind.ROOT) {
            projected = Math.min(projected, ROOT_TOKEN_LIMIT);
        }
        return projected >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) projected;
    }

    private static LsmSummarySegment activeRoot(List<LsmSummarySegment> segments) {
        List<LsmSummarySegment> roots =
                segments.stream().filter(segment -> segment.kind() == Kind.ROOT).toList();
        if (roots.size() > 1) {
            throw new IllegalArgumentException("active manifest contains more than one ROOT");
        }
        return roots.isEmpty() ? null : roots.getFirst();
    }

    private static boolean continuous(List<LsmSummarySegment> segments) {
        for (int index = 1; index < segments.size(); index++) {
            if (segments.get(index - 1).coverage().endTurnSequence() + 1L
                    != segments.get(index).coverage().startTurnSequence()) {
                return false;
            }
        }
        return true;
    }

    private static String plannedId(OperationType type, int level, Coverage coverage) {
        return PLANNED_ID_PREFIX
                + type.name()
                + ID_SEPARATOR
                + level
                + ID_SEPARATOR
                + coverage.startTurnSequence()
                + ID_SEPARATOR
                + coverage.endTurnSequence();
    }

    private static int saturatedAdd(int left, int right) {
        return left >= Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right;
    }

    private static final class PlanningState {

        private final List<LsmSummarySegment> activeSegments;
        private final List<PlannedOperation> operations = new ArrayList<>();

        private PlanningState(List<LsmSummarySegment> activeSegments) {
            this.activeSegments = activeSegments;
        }
    }

    private record WarmRange(long start, long end) {

        private static WarmRange empty() {
            return new WarmRange(0L, 0L);
        }

        private boolean present() {
            return start > 0L;
        }
    }
}
