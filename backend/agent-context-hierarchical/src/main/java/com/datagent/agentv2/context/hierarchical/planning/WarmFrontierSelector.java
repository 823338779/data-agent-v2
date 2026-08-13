package com.datagent.agentv2.context.hierarchical.planning;

import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.context.hierarchical.domain.FrontierSelection;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalSummaryNode;
import com.datagent.agentv2.context.hierarchical.domain.SummaryNodeIdentity;
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

    /** 规划时使用完整问答索引选择 frontier；覆盖不足时返回可生成的缺失范围。 */
    public FrontierSelection select(
            SummaryNodeIdentity expectedIdentity,
            List<HierarchicalSummaryNode> availableNodes,
            List<ConversationTurn> completedTurns,
            List<SummaryRange> targetRanges,
            int tokenBudget) {
        Objects.requireNonNull(expectedIdentity, "expectedIdentity");
        Objects.requireNonNull(availableNodes, "availableNodes");
        Objects.requireNonNull(completedTurns, "completedTurns");
        Objects.requireNonNull(targetRanges, "targetRanges");
        if (tokenBudget < 0) {
            throw new IllegalArgumentException("tokenBudget must not be negative");
        }
        if (targetRanges.isEmpty()) {
            return FrontierSelection.empty();
        }

        // 规划路径把消息序号映射回完整问答序号，并据此构造缺失的对齐范围。
        TurnIndex turnIndex = new TurnIndex(completedTurns);
        Map<String, Candidate> candidatesById =
                indexCandidates(availableNodes, turnIndex, expectedIdentity);
        return selectCandidates(
                expectedIdentity,
                availableNodes,
                candidatesById,
                targetRanges,
                tokenBudget,
                turnIndex::alignedRanges);
    }

    /** 发布时仅使用 durable descriptor 范围重选完整 frontier，不重新读取原始消息正文。 */
    public FrontierSelection selectResolved(
            SummaryNodeIdentity expectedIdentity,
            List<HierarchicalSummaryNode> availableNodes,
            Map<String, SummaryRange> rangesByNodeId,
            List<SummaryRange> targetRanges,
            int tokenBudget) {
        Objects.requireNonNull(expectedIdentity, "expectedIdentity");
        Objects.requireNonNull(availableNodes, "availableNodes");
        Objects.requireNonNull(rangesByNodeId, "rangesByNodeId");
        Objects.requireNonNull(targetRanges, "targetRanges");
        if (tokenBudget < 0) {
            throw new IllegalArgumentException("tokenBudget must not be negative");
        }
        if (targetRanges.isEmpty()) {
            return FrontierSelection.empty();
        }

        // descriptor 是问答序号的 durable 事实源；节点元数据必须与其逐项一致。
        Map<String, Candidate> candidatesById =
                indexCandidates(availableNodes, rangesByNodeId, expectedIdentity);
        FrontierSelection selection =
                selectCandidates(
                        expectedIdentity,
                        availableNodes,
                        candidatesById,
                        targetRanges,
                        tokenBudget,
                        WarmFrontierSelector::rejectDurableGap);
        if (!selection.publishable()) {
            throw new IllegalArgumentException("Durable Warm frontier is not publishable");
        }
        return selection;
    }

    private FrontierSelection selectCandidates(
            SummaryNodeIdentity expectedIdentity,
            List<HierarchicalSummaryNode> availableNodes,
            Map<String, Candidate> candidatesById,
            List<SummaryRange> targetRanges,
            int tokenBudget,
            MissingRangeResolver missingRangeResolver) {
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

        // 先构造覆盖目标的最粗 frontier，缺口交给调用路径决定是报告还是拒绝。
        CoverageResult coverage =
                coarsestCoverage(candidates, missingRangeResolver, targetStart, targetEnd);
        if (!coverage.missingRanges().isEmpty()) {
            return new FrontierSelection(
                    List.of(), List.of(), coverage.missingRanges(), 0, false, true);
        }

        // 从最新历史向前展开；较新的可展开节点超预算后不再把预算花在更老节点上。
        List<Candidate> frontier = new ArrayList<>(coverage.frontier());
        boolean expanded;
        boolean recencyBlocked = false;
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
                    recencyBlocked = true;
                    break;
                }
                frontier.remove(index);
                frontier.add(index, right);
                frontier.add(index, left);
                expanded = true;
                break;
            }
        } while (expanded && !recencyBlocked);

        // 规划阶段可报告尚未生成的直接父范围；发布阶段的完整 durable 树必须已满足预算。
        List<SummaryRange> missingParents =
                estimatedTokens(frontier) > tokenBudget
                        ? missingImmediateParents(frontier, candidates)
                        : List.of();
        boolean withinBudget = estimatedTokens(frontier) <= tokenBudget;
        boolean satisfiable = withinBudget || !missingParents.isEmpty();
        FrontierSelection selection =
                new FrontierSelection(
                        frontier.stream().map(Candidate::nodeId).toList(),
                        frontier.stream().map(Candidate::range).toList(),
                        missingParents,
                        estimatedTokens(frontier),
                        withinBudget,
                        satisfiable);
        if (satisfiable && missingParents.isEmpty()) {
            validator.validateFrontier(
                    selection,
                    availableNodes,
                    targetRanges,
                    targetStart - 1L,
                    null,
                    expectedIdentity);
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
        long parentCoverage = left.range().coveredTurns() * 2L;
        return left.range().level() == right.range().level()
                && left.range().endTurn() + 1L == right.range().startTurn()
                && (left.range().startTurn() - 1L) % parentCoverage == 0L
                && left.range().endMessageSequence() + 1L == right.range().startMessageSequence()
                && leftNode.conversationSessionId().equals(rightNode.conversationSessionId())
                && leftNode.summarySchemaVersion() == rightNode.summarySchemaVersion()
                && leftNode.summaryPromptVersion() == rightNode.summaryPromptVersion()
                && leftNode.modelProvider().equals(rightNode.modelProvider())
                && leftNode.modelName().equals(rightNode.modelName());
    }

    private static CoverageResult coarsestCoverage(
            List<Candidate> candidates,
            MissingRangeResolver missingRangeResolver,
            long targetStart,
            long targetEnd) {
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
            missing.addAll(missingRangeResolver.resolve(cursor, missingEnd));
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
            List<HierarchicalSummaryNode> nodes,
            TurnIndex turnIndex,
            SummaryNodeIdentity expectedIdentity) {
        Map<String, Candidate> result = new HashMap<>();
        for (HierarchicalSummaryNode node : nodes) {
            HierarchicalSummaryNode checked = Objects.requireNonNull(node, "node");
            expectedIdentity.validate(checked);
            SummaryRange range = turnIndex.rangeFor(checked);
            Candidate candidate = new Candidate(checked.nodeId(), checked, range);
            if (result.put(candidate.nodeId(), candidate) != null) {
                throw new IllegalArgumentException("Duplicate hierarchical node ID");
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, Candidate> indexCandidates(
            List<HierarchicalSummaryNode> nodes,
            Map<String, SummaryRange> rangesByNodeId,
            SummaryNodeIdentity expectedIdentity) {
        Map<String, SummaryRange> checkedRanges = Map.copyOf(rangesByNodeId);
        Map<String, Candidate> result = new HashMap<>();
        for (HierarchicalSummaryNode node : nodes) {
            HierarchicalSummaryNode checked = Objects.requireNonNull(node, "node");
            expectedIdentity.validate(checked);
            SummaryRange range = checkedRanges.get(checked.nodeId());
            if (range == null
                    || checked.level() != range.level()
                    || checked.coveredCompletedTurnCount() != range.coveredTurns()
                    || checked.startConversationMessageSequence() != range.startMessageSequence()
                    || checked.endConversationMessageSequence() != range.endMessageSequence()) {
                throw new IllegalArgumentException("Invalid durable hierarchical node range");
            }
            Candidate candidate = new Candidate(checked.nodeId(), checked, range);
            if (result.put(candidate.nodeId(), candidate) != null) {
                throw new IllegalArgumentException("Duplicate hierarchical node ID");
            }
        }
        if (result.size() != checkedRanges.size()) {
            throw new IllegalArgumentException("Durable hierarchical node range is missing");
        }
        return Map.copyOf(result);
    }

    private static List<SummaryRange> rejectDurableGap(long startTurn, long endTurn) {
        throw new IllegalArgumentException("Durable Warm frontier contains a coverage gap");
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

    @FunctionalInterface
    private interface MissingRangeResolver {

        List<SummaryRange> resolve(long startTurn, long endTurn);
    }

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
