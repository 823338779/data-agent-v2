package com.datagent.agentv2.context.lsm.planning;

import com.datagent.agentv2.context.lsm.domain.FrontierSelection;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 在给定 Warm 预算内，确定性选择“远粗近细”的连续 Input Frontier。 */
public final class WarmSegmentFrontierSelector {

    private static final Comparator<LsmSummarySegment> CHRONOLOGICAL =
            Comparator.comparingLong(
                            (LsmSummarySegment segment) -> segment.coverage().startTurnSequence())
                    .thenComparingLong(segment -> segment.coverage().endTurnSequence())
                    .thenComparingInt(LsmSummarySegment::level)
                    .thenComparing(LsmSummarySegment::segmentId);

    private final LsmInvariantValidator validator = new LsmInvariantValidator();

    /** 选择完整覆盖目标区间且不超过预算的有序摘要段；不存在合法表示时明确失败。 */
    public FrontierSelection select(
            List<LsmSummarySegment> availableSegments,
            LsmSummarySegment.Coverage targetCoverage,
            int tokenBudget) {
        Objects.requireNonNull(availableSegments, "availableSegments");
        Objects.requireNonNull(targetCoverage, "targetCoverage");
        if (tokenBudget < 0) {
            throw new IllegalArgumentException("tokenBudget must not be negative");
        }

        // 最细 frontier 由目标区间内没有可用直接子节点的叶节点构成。
        Map<String, LsmSummarySegment> byId = indexById(availableSegments);
        List<LsmSummarySegment> frontier =
                availableSegments.stream()
                        .filter(segment -> inside(segment.coverage(), targetCoverage))
                        .filter(segment -> segment.sourceSegmentIds().isEmpty())
                        .sorted(CHRONOLOGICAL)
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        validateExactCoverage(frontier, targetCoverage);

        // 预算不足时按父覆盖起点从旧到新替换完整子组；同起点优先使用更高层父节点。
        List<LsmSummarySegment> parents =
                availableSegments.stream()
                        .filter(segment -> inside(segment.coverage(), targetCoverage))
                        .filter(segment -> !segment.sourceSegmentIds().isEmpty())
                        .sorted(
                                Comparator.comparingLong(
                                                (LsmSummarySegment segment) ->
                                                        segment.coverage().startTurnSequence())
                                        .thenComparing(
                                                Comparator.comparingInt(LsmSummarySegment::level)
                                                        .reversed())
                                        .thenComparing(LsmSummarySegment::segmentId))
                        .toList();
        while (estimatedTokens(frontier) > tokenBudget) {
            LsmSummarySegment replacement = firstUsefulParent(parents, frontier, byId);
            if (replacement == null) {
                throw new IllegalStateException(
                        "available segments cannot satisfy Warm token budget");
            }
            replaceDescendants(frontier, replacement);
            frontier.sort(CHRONOLOGICAL);
        }

        FrontierSelection selection =
                new FrontierSelection(
                        frontier.stream().map(LsmSummarySegment::segmentId).toList(),
                        frontier.stream().map(LsmSummarySegment::coverage).toList(),
                        estimatedTokens(frontier));
        validator.assertContinuous(selection.coverage());
        validator.assertNoParentChild(selection, availableSegments);
        return selection;
    }

    private static LsmSummarySegment firstUsefulParent(
            List<LsmSummarySegment> parents,
            List<LsmSummarySegment> frontier,
            Map<String, LsmSummarySegment> byId) {
        Set<String> selectedIds =
                frontier.stream()
                        .map(LsmSummarySegment::segmentId)
                        .collect(java.util.stream.Collectors.toSet());
        for (LsmSummarySegment parent : parents) {
            if (selectedIds.contains(parent.segmentId())) {
                continue;
            }
            Set<String> selectedDescendants = new HashSet<>();
            collectSelectedDescendants(parent, selectedIds, byId, selectedDescendants);
            if (selectedDescendants.isEmpty()) {
                continue;
            }
            long descendantTokens =
                    frontier.stream()
                            .filter(segment -> selectedDescendants.contains(segment.segmentId()))
                            .mapToLong(LsmSummarySegment::estimatedTokenCount)
                            .sum();
            long descendantTurns =
                    frontier.stream()
                            .filter(segment -> selectedDescendants.contains(segment.segmentId()))
                            .mapToLong(LsmSummarySegment::turnCount)
                            .sum();
            if (descendantTurns == parent.turnCount()
                    && parent.estimatedTokenCount() < descendantTokens) {
                return parent;
            }
        }
        return null;
    }

    private static void collectSelectedDescendants(
            LsmSummarySegment parent,
            Set<String> selectedIds,
            Map<String, LsmSummarySegment> byId,
            Set<String> result) {
        for (String sourceId : parent.sourceSegmentIds()) {
            if (selectedIds.contains(sourceId)) {
                result.add(sourceId);
                continue;
            }
            LsmSummarySegment source = byId.get(sourceId);
            if (source != null) {
                collectSelectedDescendants(source, selectedIds, byId, result);
            }
        }
    }

    private static void replaceDescendants(
            List<LsmSummarySegment> frontier, LsmSummarySegment parent) {
        frontier.removeIf(
                segment ->
                        segment.coverage().startTurnSequence()
                                        >= parent.coverage().startTurnSequence()
                                && segment.coverage().endTurnSequence()
                                        <= parent.coverage().endTurnSequence());
        frontier.add(parent);
    }

    private static void validateExactCoverage(
            List<LsmSummarySegment> frontier, LsmSummarySegment.Coverage targetCoverage) {
        if (frontier.isEmpty()
                || frontier.getFirst().coverage().startTurnSequence()
                        != targetCoverage.startTurnSequence()
                || frontier.getLast().coverage().endTurnSequence()
                        != targetCoverage.endTurnSequence()) {
            throw new IllegalStateException(
                    "available segments do not cover the requested Warm range");
        }
        new LsmInvariantValidator()
                .assertContinuous(frontier.stream().map(LsmSummarySegment::coverage).toList());
    }

    private static boolean inside(
            LsmSummarySegment.Coverage candidate, LsmSummarySegment.Coverage target) {
        return candidate.startTurnSequence() >= target.startTurnSequence()
                && candidate.endTurnSequence() <= target.endTurnSequence();
    }

    private static int estimatedTokens(List<LsmSummarySegment> segments) {
        long total = segments.stream().mapToLong(LsmSummarySegment::estimatedTokenCount).sum();
        return total >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    private static Map<String, LsmSummarySegment> indexById(List<LsmSummarySegment> segments) {
        Map<String, LsmSummarySegment> byId = new HashMap<>();
        for (LsmSummarySegment segment : segments) {
            LsmSummarySegment checked = Objects.requireNonNull(segment, "segment");
            if (byId.put(checked.segmentId(), checked) != null) {
                throw new IllegalArgumentException("duplicate segmentId: " + checked.segmentId());
            }
        }
        return byId;
    }
}
