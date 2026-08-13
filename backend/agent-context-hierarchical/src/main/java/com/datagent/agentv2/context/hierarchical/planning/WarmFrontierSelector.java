package com.datagent.agentv2.context.hierarchical.planning;

import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.context.hierarchical.domain.FrontierSelection;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalSummaryNode;
import com.datagent.agentv2.context.hierarchical.domain.SummaryRange;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 在 Warm 预算内选择完整、远粗近细且只读取现有节点的二叉 frontier。 */
public final class WarmFrontierSelector {

    private static final Comparator<Candidate> CHRONOLOGICAL =
            Comparator.comparingLong((Candidate candidate) -> candidate.range().startTurn())
                    .thenComparingLong(candidate -> candidate.range().endTurn())
                    .thenComparing(Candidate::nodeId);

    private final HierarchicalInvariantValidator validator = new HierarchicalInvariantValidator();

    /** 选择可发布 frontier；覆盖不足时仅报告缺失范围，不生成摘要或发布部分结果。 */
    public FrontierSelection select(
            List<HierarchicalSummaryNode> availableNodes,
            List<ConversationTurn> completedTurns,
            List<SummaryRange> targetRanges,
            int tokenBudget) {
        Objects.requireNonNull(availableNodes, "availableNodes");
        Objects.requireNonNull(completedTurns, "completedTurns");
        Objects.requireNonNull(targetRanges, "targetRanges");
        if (tokenBudget < 0) {
            throw new IllegalArgumentException("tokenBudget must not be negative");
        }
        if (targetRanges.isEmpty()) {
            return FrontierSelection.empty();
        }

        // 将消息序号覆盖映射回连续完整问答范围，并拒绝不匹配的伪二叉节点。
        TurnIndex turnIndex = new TurnIndex(completedTurns);
        Map<String, Candidate> candidatesById = indexCandidates(availableNodes, turnIndex);
        long targetStart = targetRanges.getFirst().startTurn();
        long targetEnd = targetRanges.getLast().endTurn();
        List<Candidate> candidates =
                candidatesById.values().stream()
                        .filter(
                                candidate ->
                                        candidate.range().startTurn() >= targetStart
                                                && candidate.range().endTurn() <= targetEnd)
                        .sorted(CHRONOLOGICAL)
                        .toList();

        // 先选择每个时间游标可用的最粗节点；没有覆盖时以最大对齐范围记录缺口。
        CoverageResult coverage = coarsestCoverage(candidates, turnIndex, targetStart, targetEnd);
        if (!coverage.missingRanges().isEmpty()) {
            return new FrontierSelection(List.of(), List.of(), coverage.missingRanges(), 0, false);
        }

        // 从最新历史向前展开；每次仅用两个直接子节点完整替换父节点。
        List<Candidate> frontier = new ArrayList<>(coverage.frontier());
        boolean expanded;
        do {
            expanded = false;
            for (int index = frontier.size() - 1; index >= 0; index--) {
                Candidate parent = frontier.get(index);
                if (parent.node().level() == 0) {
                    continue;
                }
                Candidate left = candidatesById.get(parent.node().leftChildNodeId());
                Candidate right = candidatesById.get(parent.node().rightChildNodeId());
                if (!areDirectChildren(parent, left, right)) {
                    continue;
                }
                int expandedTokens =
                        saturatedAdd(
                                estimatedTokens(frontier) - parent.node().estimatedTokenCount(),
                                saturatedAdd(
                                        left.node().estimatedTokenCount(),
                                        right.node().estimatedTokenCount()));
                if (expandedTokens > tokenBudget) {
                    continue;
                }
                frontier.remove(index);
                frontier.add(index, right);
                frontier.add(index, left);
                expanded = true;
                break;
            }
        } while (expanded);

        // 现有节点仍超预算时，报告可自底向上生成的最老优先父范围，由协调器后续物化。
        List<SummaryRange> missingParents =
                estimatedTokens(frontier) > tokenBudget
                        ? missingImmediateParents(frontier, candidates)
                        : List.of();
        FrontierSelection selection =
                new FrontierSelection(
                        frontier.stream().map(Candidate::nodeId).toList(),
                        frontier.stream().map(Candidate::range).toList(),
                        missingParents,
                        estimatedTokens(frontier),
                        estimatedTokens(frontier) <= tokenBudget);
        if (missingParents.isEmpty()) {
            validator.validateFrontier(
                    selection, availableNodes, targetRanges, targetStart - 1L, null);
        }
        return selection;
    }

    private static List<SummaryRange> missingImmediateParents(
            List<Candidate> frontier, List<Candidate> availableCandidates) {
        List<SummaryRange> missing = new ArrayList<>();
        for (int index = 0; index + 1 < frontier.size(); index++) {
            Candidate left = frontier.get(index);
            Candidate right = frontier.get(index + 1);
            if (!canFormParent(left, right)) {
                continue;
            }
            SummaryRange parent =
                    new SummaryRange(
                            left.range().startTurn(),
                            right.range().endTurn(),
                            left.range().startMessageSequence(),
                            right.range().endMessageSequence(),
                            left.range().level() + 1);
            boolean parentAvailable =
                    availableCandidates.stream()
                            .anyMatch(candidate -> candidate.range().equals(parent));
            if (!parentAvailable) {
                missing.add(parent);
            }
            index++;
        }
        return List.copyOf(missing);
    }

    private static boolean canFormParent(Candidate left, Candidate right) {
        HierarchicalSummaryNode leftNode = left.node();
        HierarchicalSummaryNode rightNode = right.node();
        return left.range().level() == right.range().level()
                && left.range().endTurn() + 1L == right.range().startTurn()
                && left.range().endMessageSequence() < right.range().startMessageSequence()
                && leftNode.conversationSessionId().equals(rightNode.conversationSessionId())
                && leftNode.summarySchemaVersion() == rightNode.summarySchemaVersion()
                && leftNode.summaryPromptVersion() == rightNode.summaryPromptVersion()
                && leftNode.modelProvider().equals(rightNode.modelProvider())
                && leftNode.modelName().equals(rightNode.modelName());
    }

    private static CoverageResult coarsestCoverage(
            List<Candidate> candidates, TurnIndex turnIndex, long targetStart, long targetEnd) {
        List<Candidate> frontier = new ArrayList<>();
        List<SummaryRange> missing = new ArrayList<>();
        long cursor = targetStart;
        while (cursor <= targetEnd) {
            long current = cursor;
            Candidate selected =
                    candidates.stream()
                            .filter(candidate -> candidate.range().startTurn() == current)
                            .max(
                                    Comparator.comparingLong(
                                                    (Candidate candidate) ->
                                                            candidate.range().coveredTurns())
                                            .thenComparing(Candidate::nodeId))
                            .orElse(null);
            if (selected != null) {
                frontier.add(selected);
                cursor = selected.range().endTurn() + 1L;
                continue;
            }

            long nextCoveredStart =
                    candidates.stream()
                            .map(Candidate::range)
                            .mapToLong(SummaryRange::startTurn)
                            .filter(start -> start > current)
                            .min()
                            .orElse(targetEnd + 1L);
            long missingEnd = Math.min(targetEnd, nextCoveredStart - 1L);
            missing.addAll(turnIndex.alignedRanges(cursor, missingEnd));
            cursor = missingEnd + 1L;
        }
        return new CoverageResult(List.copyOf(frontier), List.copyOf(missing));
    }

    private static boolean areDirectChildren(Candidate parent, Candidate left, Candidate right) {
        return left != null
                && right != null
                && left.range().level() + 1 == parent.range().level()
                && right.range().level() == left.range().level()
                && left.range().startTurn() == parent.range().startTurn()
                && left.range().endTurn() + 1L == right.range().startTurn()
                && right.range().endTurn() == parent.range().endTurn();
    }

    private static Map<String, Candidate> indexCandidates(
            List<HierarchicalSummaryNode> nodes, TurnIndex turnIndex) {
        Map<String, Candidate> result = new HashMap<>();
        for (HierarchicalSummaryNode node : nodes) {
            HierarchicalSummaryNode checked = Objects.requireNonNull(node, "node");
            SummaryRange range = turnIndex.rangeFor(checked);
            Candidate candidate = new Candidate(checked.nodeId(), checked, range);
            if (result.put(candidate.nodeId(), candidate) != null) {
                throw new IllegalArgumentException("Duplicate hierarchical node ID");
            }
        }
        return Map.copyOf(result);
    }

    private static int estimatedTokens(List<Candidate> frontier) {
        int total = 0;
        for (Candidate candidate : frontier) {
            total = saturatedAdd(total, candidate.node().estimatedTokenCount());
        }
        return total;
    }

    private static int saturatedAdd(int left, int right) {
        long total = (long) left + right;
        return total >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    private record Candidate(String nodeId, HierarchicalSummaryNode node, SummaryRange range) {}

    private record CoverageResult(List<Candidate> frontier, List<SummaryRange> missingRanges) {}

    /** 提供消息序号与完整问答序号之间唯一、连续的映射。 */
    private static final class TurnIndex {

        private final List<ConversationTurn> turns;
        private final Map<Long, ConversationTurn> byStartSequence;
        private final Map<Long, ConversationTurn> byEndSequence;

        private TurnIndex(List<ConversationTurn> completedTurns) {
            this.turns =
                    completedTurns.stream()
                            .map(turn -> Objects.requireNonNull(turn, "turn"))
                            .filter(ConversationTurn::completed)
                            .sorted(Comparator.comparingLong(ConversationTurn::ordinal))
                            .toList();
            this.byStartSequence = new HashMap<>();
            this.byEndSequence = new HashMap<>();
            for (ConversationTurn turn : turns) {
                byStartSequence.put(turn.startMessageSequence(), turn);
                byEndSequence.put(turn.endMessageSequence(), turn);
            }
        }

        private SummaryRange rangeFor(HierarchicalSummaryNode node) {
            ConversationTurn first = byStartSequence.get(node.startConversationMessageSequence());
            ConversationTurn last = byEndSequence.get(node.endConversationMessageSequence());
            if (first == null || last == null) {
                throw new IllegalArgumentException("Node boundary does not match completed turns");
            }
            return new SummaryRange(
                    first.ordinal(),
                    last.ordinal(),
                    first.startMessageSequence(),
                    last.endMessageSequence(),
                    node.level());
        }

        private List<SummaryRange> alignedRanges(long startTurn, long endTurn) {
            List<SummaryRange> ranges = new ArrayList<>();
            long cursor = startTurn;
            while (cursor <= endTurn) {
                long remaining = endTurn - cursor + 1L;
                long zeroBasedStart = cursor - 1L;
                int alignment =
                        zeroBasedStart == 0L ? 62 : Long.numberOfTrailingZeros(zeroBasedStart);
                int level = Math.min(63 - Long.numberOfLeadingZeros(remaining), alignment);
                long coverage = 1L << level;
                ConversationTurn first = turns.get(Math.toIntExact(cursor - 1L));
                ConversationTurn last = turns.get(Math.toIntExact(cursor + coverage - 2L));
                ranges.add(
                        new SummaryRange(
                                cursor,
                                cursor + coverage - 1L,
                                first.startMessageSequence(),
                                last.endMessageSequence(),
                                level));
                cursor += coverage;
            }
            return List.copyOf(ranges);
        }
    }
}
