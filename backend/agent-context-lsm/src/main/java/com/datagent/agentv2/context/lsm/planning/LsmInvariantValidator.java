package com.datagent.agentv2.context.lsm.planning;

import com.datagent.agentv2.context.lsm.domain.FrontierSelection;
import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan;
import com.datagent.agentv2.context.lsm.domain.LsmPlanningInput;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 集中校验成功发布和 Input Frontier 必须保持的结构不变量。 */
public final class LsmInvariantValidator {

    private static final int MIN_WARM_TURNS = 100;
    private static final int MAX_WARM_TURNS = 109;

    /** 断言区间按时间连续、无重叠且无缺口。 */
    public void assertContinuous(List<LsmSummarySegment.Coverage> coverage) {
        Objects.requireNonNull(coverage, "coverage");
        for (int index = 1; index < coverage.size(); index++) {
            LsmSummarySegment.Coverage previous = coverage.get(index - 1);
            LsmSummarySegment.Coverage current = coverage.get(index);
            if (previous.endTurnSequence() + 1L != current.startTurnSequence()) {
                throw new IllegalStateException("coverage must be continuous and non-overlapping");
            }
        }
    }

    /** 断言所选 Input Frontier 不同时包含任意父节点和后代节点。 */
    public void assertNoParentChild(
            FrontierSelection selection, List<LsmSummarySegment> availableSegments) {
        Objects.requireNonNull(selection, "selection");
        Map<String, LsmSummarySegment> byId = indexById(availableSegments);
        Set<String> selected = Set.copyOf(selection.segmentIds());
        for (String segmentId : selection.segmentIds()) {
            assertNoSelectedDescendant(segmentId, selected, byId, new HashSet<>());
        }
    }

    /** 校验 planner 输出可直接作为一次原子发布的候选状态。 */
    public void validatePlan(LsmPlanningInput input, LsmCompactionPlan plan) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(plan, "plan");
        List<LsmSummarySegment> projected = plan.projectedSegments();
        List<LsmSummarySegment.Coverage> orderedCoverage =
                projected.stream().map(LsmSummarySegment::coverage).toList();
        assertContinuous(orderedCoverage);
        if (!projected.isEmpty() && projected.getFirst().coverage().startTurnSequence() != 1L) {
            throw new IllegalStateException("published coverage must start at the first turn");
        }
        assertManifestReferencesProjectedSegments(plan, projected);
        assertCoverageReachesHotBoundary(plan, projected);

        // 普通层（含配置变化遗留高层）严格受 fanout-1 限制，活动 ROOT 始终最多一个。
        int fanout = input.policy().fanout();
        Map<Integer, Long> ordinaryLevelCounts = new HashMap<>();
        for (LsmSummarySegment segment : projected) {
            if (segment.kind() == LsmSummarySegment.SegmentKind.STANDARD) {
                ordinaryLevelCounts.merge(segment.level(), 1L, Long::sum);
            }
        }
        for (long count : ordinaryLevelCounts.values()) {
            if (count > fanout - 1L) {
                throw new IllegalStateException("ordinary level exceeds fanout minus one");
            }
        }
        long rootCount =
                projected.stream()
                        .filter(segment -> segment.kind() == LsmSummarySegment.SegmentKind.ROOT)
                        .count();
        if (rootCount > 1L) {
            throw new IllegalStateException("published manifest must contain at most one ROOT");
        }
        projected.stream()
                .filter(segment -> segment.kind() == LsmSummarySegment.SegmentKind.ROOT)
                .findFirst()
                .ifPresent(
                        root -> {
                            if (root.coverage().startTurnSequence() != 1L) {
                                throw new IllegalStateException(
                                        "active ROOT must cover the conversation prefix");
                            }
                            if (!root.segmentId()
                                    .equals(plan.proposedManifest().activeRootSegmentId())) {
                                throw new IllegalStateException(
                                        "manifest active ROOT must reference projected ROOT");
                            }
                        });
        // 历史足够长时，每次成功发布必须把 Warm 收敛到 100～109 轮且保护十轮 Hot。
        long completedTurns = input.turns().stream().filter(turn -> turn.completed()).count();
        if (completedTurns >= MIN_WARM_TURNS + 10L) {
            long warmTurns = plan.proposedManifest().warmTurnCount();
            if (warmTurns < MIN_WARM_TURNS || warmTurns > MAX_WARM_TURNS) {
                throw new IllegalStateException(
                        "published Warm range must contain 100 to 109 turns");
            }
        }
        int expectedHot = (int) Math.min(10L, completedTurns);
        if (plan.hotBoundary().completedTurnCount() != expectedHot) {
            throw new IllegalStateException("Hot must protect the last ten completed turns");
        }
    }

    private static void assertManifestReferencesProjectedSegments(
            LsmCompactionPlan plan, List<LsmSummarySegment> projected) {
        List<String> projectedIds = projected.stream().map(LsmSummarySegment::segmentId).toList();
        if (!projectedIds.equals(plan.proposedManifest().activeSegmentIds())) {
            throw new IllegalStateException(
                    "manifest active segment IDs must match projected Storage Frontier");
        }
    }

    private static void assertCoverageReachesHotBoundary(
            LsmCompactionPlan plan, List<LsmSummarySegment> projected) {
        Long hotStart = plan.hotBoundary().startTurnSequence();
        if (hotStart == null) {
            return;
        }
        long expectedHistoryEnd = hotStart - 1L;
        long activeEnd =
                projected.isEmpty() ? 0L : projected.getLast().coverage().endTurnSequence();
        Long pendingStart = plan.proposedManifest().pendingRawStartTurnSequence();
        Long pendingEnd = plan.proposedManifest().pendingRawEndTurnSequence();

        // 活动摘要和 pending raw 必须无缝覆盖到 Hot 的前一轮，不能静默遗漏尾部历史。
        if (pendingStart == null) {
            if (activeEnd != expectedHistoryEnd) {
                throw new IllegalStateException("published coverage must reach the Hot boundary");
            }
            return;
        }
        if (pendingStart != activeEnd + 1L || pendingEnd != expectedHistoryEnd) {
            throw new IllegalStateException("pending raw must continuously reach the Hot boundary");
        }
    }

    private static Map<String, LsmSummarySegment> indexById(List<LsmSummarySegment> segments) {
        Objects.requireNonNull(segments, "segments");
        Map<String, LsmSummarySegment> byId = new HashMap<>();
        for (LsmSummarySegment segment : segments) {
            LsmSummarySegment previous = byId.put(segment.segmentId(), segment);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate segmentId: " + segment.segmentId());
            }
        }
        return byId;
    }

    private static void assertNoSelectedDescendant(
            String segmentId,
            Set<String> selected,
            Map<String, LsmSummarySegment> byId,
            Set<String> visiting) {
        LsmSummarySegment segment = byId.get(segmentId);
        if (segment == null || !visiting.add(segmentId)) {
            return;
        }
        for (String sourceId : segment.sourceSegmentIds()) {
            if (selected.contains(sourceId)) {
                throw new IllegalStateException("frontier must not contain a parent and child");
            }
            assertNoSelectedDescendant(sourceId, selected, byId, visiting);
        }
        visiting.remove(segmentId);
    }
}
