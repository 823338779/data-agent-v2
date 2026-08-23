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
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment.SegmentKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 按固定顺序规划 L0 封存、size-tiered 晋升和 Major Compaction。 */
public final class LsmCompactionPlanner {

    private static final int HOT_COMPLETED_TURNS = 10;
    private static final int BASE_SEGMENT_TURNS = 10;
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
        coordinateHierarchy(
                input.policy().fanout(),
                input.policy().maxLevel(),
                input.historyMemoryBudget(),
                state);
        planMajorCompactionWhenUseful(input, state);

        LsmContextManifest proposed = proposedManifest(input, hot, state);
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
                        SegmentKind.STANDARD,
                        0,
                        coverage,
                        partial,
                        tokens,
                        targetRenderedTokens(input, tokens));
        state.operations.add(operation);
        state.activeSegments.add(
                new LsmSummarySegment(
                        targetId, SegmentKind.STANDARD, 0, coverage, tokens, List.of(), partial));
        state.activeSegments.sort(CHRONOLOGICAL);
    }

    private static void coordinateHierarchy(
            int fanout, int maxLevel, int historyMemoryBudget, PlanningState state) {
        while (true) {
            LsmSummarySegment root = activeRoot(state.activeSegments);

            // ROOT 仅吸收时间前缀中的 fanout-1 个同层节点，优先吸收可避免把完整前缀错误地普通晋升。
            List<LsmSummarySegment> rootSources =
                    rootAbsorptionSources(state.activeSegments, root, fanout, maxLevel);
            if (!rootSources.isEmpty()) {
                int tailLevel = rootSources.get(1).level();
                OperationType operationType =
                        tailLevel == maxLevel - 1 && root.level() == maxLevel
                                ? OperationType.ROLL_ROOT
                                : OperationType.RECONCILE_CONFIGURATION;
                mergeSources(
                        state,
                        rootSources,
                        operationType,
                        SegmentKind.ROOT,
                        maxLevel,
                        NORMAL_SUMMARY_RATIO,
                        historyMemoryBudget);
                continue;
            }

            // 前缀不足时从最低可 carry 层推进一批，逐层补齐 ROOT 前的高层前缀后重新协调。
            List<LsmSummarySegment> promotion = firstPromotionBatch(state.activeSegments, fanout);
            if (promotion.isEmpty()) {
                return;
            }
            int sourceLevel = promotion.getFirst().level();
            boolean createsRoot =
                    root == null
                            && promotion.getFirst().coverage().startTurnSequence() == 1L
                            && sourceLevel + 1 >= maxLevel;

            // 普通 carry 始终使用恰好 fanout 个连续同层节点；只有覆盖前缀到达配置顶层时才首次形成 ROOT。
            mergeSources(
                    state,
                    promotion,
                    createsRoot ? OperationType.ROLL_ROOT : OperationType.PROMOTE,
                    createsRoot ? SegmentKind.ROOT : SegmentKind.STANDARD,
                    createsRoot ? maxLevel : sourceLevel + 1,
                    NORMAL_SUMMARY_RATIO,
                    historyMemoryBudget);
        }
    }

    private static List<LsmSummarySegment> rootAbsorptionSources(
            List<LsmSummarySegment> activeSegments,
            LsmSummarySegment root,
            int fanout,
            int maxLevel) {
        if (root == null || activeSegments.getFirst() != root || activeSegments.size() < fanout) {
            return List.of();
        }
        List<LsmSummarySegment> sources = List.copyOf(activeSegments.subList(0, fanout));
        int tailLevel = sources.get(1).level();
        if (tailLevel < maxLevel - 1) {
            return List.of();
        }
        for (int index = 1; index < sources.size(); index++) {
            LsmSummarySegment tail = sources.get(index);
            if (tail.kind() != SegmentKind.STANDARD || tail.level() != tailLevel) {
                return List.of();
            }
        }
        return continuous(sources) ? sources : List.of();
    }

    private static void planMajorCompactionWhenUseful(LsmPlanningInput input, PlanningState state) {
        if (!input.pressure().aboveTarget()) {
            return;
        }
        List<LsmSummarySegment> coldNodes =
                state.activeSegments.stream()
                        .filter(segment -> segment.kind() == SegmentKind.STANDARD)
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
        int projectedTokens = projectedTokens(sources, SegmentKind.ROOT, MAJOR_SUMMARY_RATIO);
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
                SegmentKind.ROOT,
                input.policy().maxLevel(),
                MAJOR_SUMMARY_RATIO,
                input.historyMemoryBudget());
    }

    private static LsmContextManifest proposedManifest(
            LsmPlanningInput input, HotBoundary hot, PlanningState state) {
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

    private static List<LsmSummarySegment> firstPromotionBatch(
            List<LsmSummarySegment> activeSegments, int fanout) {
        List<Integer> levels =
                activeSegments.stream()
                        .filter(segment -> segment.kind() == SegmentKind.STANDARD)
                        .map(LsmSummarySegment::level)
                        .distinct()
                        .sorted()
                        .toList();
        for (int level : levels) {
            List<LsmSummarySegment> candidates =
                    activeSegments.stream()
                            .filter(segment -> segment.kind() == SegmentKind.STANDARD)
                            .filter(segment -> segment.level() == level)
                            .sorted(CHRONOLOGICAL)
                            .toList();
            for (int start = 0; start + fanout <= candidates.size(); start++) {
                List<LsmSummarySegment> batch = candidates.subList(start, start + fanout);
                if (continuous(batch)) {
                    return List.copyOf(batch);
                }
            }
        }
        return List.of();
    }

    private static void mergeSources(
            PlanningState state,
            List<LsmSummarySegment> sources,
            OperationType type,
            SegmentKind targetKind,
            int targetLevel,
            double summaryRatio,
            int historyMemoryBudget) {
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
                        tokens,
                        Math.max(1, Math.min(historyMemoryBudget, Math.max(256, tokens))));

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
            List<LsmSummarySegment> sources, SegmentKind targetKind, double ratio) {
        long sourceTokens =
                sources.stream().mapToLong(LsmSummarySegment::estimatedTokenCount).sum();
        long projected = (long) Math.ceil(sourceTokens * ratio);
        if (targetKind == SegmentKind.ROOT) {
            projected = Math.min(projected, ROOT_TOKEN_LIMIT);
        }
        return projected >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) projected;
    }

    private static LsmSummarySegment activeRoot(List<LsmSummarySegment> segments) {
        List<LsmSummarySegment> roots =
                segments.stream().filter(segment -> segment.kind() == SegmentKind.ROOT).toList();
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

    private static int targetRenderedTokens(LsmPlanningInput input, int estimatedTokens) {
        return Math.max(1, Math.min(input.historyMemoryBudget(), Math.max(256, estimatedTokens)));
    }

    private static final class PlanningState {

        private final List<LsmSummarySegment> activeSegments;
        private final List<PlannedOperation> operations = new ArrayList<>();

        private PlanningState(List<LsmSummarySegment> activeSegments) {
            this.activeSegments = activeSegments;
        }
    }
}
