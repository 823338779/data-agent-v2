package com.datagent.agentv2.context.hierarchical.planning;

import com.datagent.agentv2.context.hierarchical.domain.FrontierSelection;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalSummaryNode;
import com.datagent.agentv2.context.hierarchical.domain.SummaryRange;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 在候选 frontier 发布前集中校验二进制覆盖和保护边界不变量。 */
public final class HierarchicalInvariantValidator {

    /** 校验范围严格连续、位于 Cold 指针之后且不包含当前未完成问答。 */
    public void validateRanges(
            List<SummaryRange> ranges,
            long expectedStartTurn,
            long expectedEndTurn,
            long coldPointerTurn,
            Long currentIncompleteTurn) {
        Objects.requireNonNull(ranges, "ranges");
        if (expectedStartTurn <= coldPointerTurn) {
            throw new IllegalArgumentException("Frontier starts at or before the Cold pointer");
        }
        long expectedCursor = expectedStartTurn;
        for (SummaryRange range : ranges) {
            Objects.requireNonNull(range, "range");
            if (range.startTurn() != expectedCursor) {
                throw new IllegalArgumentException("Frontier contains a gap or overlap");
            }
            if (currentIncompleteTurn != null
                    && range.startTurn() <= currentIncompleteTurn
                    && range.endTurn() >= currentIncompleteTurn) {
                throw new IllegalArgumentException("Frontier contains the incomplete current turn");
            }
            expectedCursor = range.endTurn() + 1L;
        }
        if (expectedCursor != expectedEndTurn + 1L) {
            throw new IllegalArgumentException("Frontier does not cover the requested range");
        }
    }

    /** 校验已发布选择的拓扑、目标覆盖和父子互斥关系。 */
    public void validateFrontier(
            FrontierSelection selection,
            List<HierarchicalSummaryNode> availableNodes,
            List<SummaryRange> targetRanges,
            long coldPointerTurn,
            Long currentIncompleteTurn) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(availableNodes, "availableNodes");
        Objects.requireNonNull(targetRanges, "targetRanges");
        if (!selection.missingRanges().isEmpty()) {
            throw new IllegalArgumentException("Missing ranges cannot be published");
        }
        if (targetRanges.isEmpty()) {
            if (!selection.ranges().isEmpty()) {
                throw new IllegalArgumentException("Frontier exists outside the Warm range");
            }
            return;
        }

        // 范围必须完整覆盖 Warm；记录节点必须与所选 ID 和范围一一对应。
        validateRanges(
                selection.ranges(),
                targetRanges.getFirst().startTurn(),
                targetRanges.getLast().endTurn(),
                coldPointerTurn,
                currentIncompleteTurn);
        Map<String, HierarchicalSummaryNode> nodesById = new HashMap<>();
        for (HierarchicalSummaryNode node : availableNodes) {
            if (nodesById.put(node.nodeId(), node) != null) {
                throw new IllegalArgumentException("Duplicate hierarchical node ID");
            }
        }
        for (int index = 0; index < selection.nodeIds().size(); index++) {
            HierarchicalSummaryNode node = nodesById.get(selection.nodeIds().get(index));
            SummaryRange range = selection.ranges().get(index);
            if (node == null
                    || node.level() != range.level()
                    || node.coveredCompletedTurnCount() != range.coveredTurns()
                    || node.startConversationMessageSequence() != range.startMessageSequence()
                    || node.endConversationMessageSequence() != range.endMessageSequence()) {
                throw new IllegalArgumentException("Frontier node does not match its range");
            }
        }

        // 任一被选节点都不能通过 child ID 链包含另一个被选节点。
        Set<String> selectedIds = new HashSet<>(selection.nodeIds());
        for (String nodeId : selection.nodeIds()) {
            assertNoSelectedDescendant(nodesById.get(nodeId), selectedIds, nodesById);
        }
    }

    private static void assertNoSelectedDescendant(
            HierarchicalSummaryNode node,
            Set<String> selectedIds,
            Map<String, HierarchicalSummaryNode> nodesById) {
        if (node == null || node.level() == 0) {
            return;
        }
        for (String childId : List.of(node.leftChildNodeId(), node.rightChildNodeId())) {
            if (selectedIds.contains(childId)) {
                throw new IllegalArgumentException("Frontier contains both parent and child");
            }
            assertNoSelectedDescendant(nodesById.get(childId), selectedIds, nodesById);
        }
    }
}
