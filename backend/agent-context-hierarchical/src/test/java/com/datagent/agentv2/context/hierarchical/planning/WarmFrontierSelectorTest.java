package com.datagent.agentv2.context.hierarchical.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.context.hierarchical.domain.FrontierSelection;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalSummaryNode;
import com.datagent.agentv2.context.hierarchical.domain.SummaryNodeIdentity;
import com.datagent.agentv2.context.hierarchical.domain.SummaryRange;
import com.datagent.agentv2.conversation.context.ContextMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WarmFrontierSelectorTest {

    private static final String SESSION_ID = "session-1";
    private static final String SUMMARY = "{}";
    private static final String PROVIDER = "test-provider";
    private static final String MODEL = "test-model";
    private static final SummaryNodeIdentity IDENTITY =
            new SummaryNodeIdentity(SESSION_ID, 1, 1, PROVIDER, MODEL);

    private final WarmFrontierSelector selector = new WarmFrontierSelector();

    @Test
    void coversWarmChronologicallyWithoutGapsOverlapOrParentChildDuplication() {
        Tree tree = tree(1, 8);

        FrontierSelection selection =
                selector.select(
                        IDENTITY, tree.nodes(), tree.turns(), List.of(tree.rootRange()), 17);

        assertThat(selection.publishable()).isTrue();
        assertThat(selection.nodeIds()).containsExactly("n-1-4", "n-5-6", "n-7", "n-8");
        assertThat(selection.ranges().getFirst().startTurn()).isEqualTo(1);
        assertThat(selection.ranges().getLast().endTurn()).isEqualTo(8);
        new HierarchicalInvariantValidator()
                .validateFrontier(
                        selection, tree.nodes(), List.of(tree.rootRange()), 0, null, IDENTITY);
    }

    @Test
    void expandsNewerHistoryBeforeOlderHistoryWhenBudgetIncreases() {
        Tree tree = tree(1, 8);

        FrontierSelection tight =
                selector.select(
                        IDENTITY, tree.nodes(), tree.turns(), List.of(tree.rootRange()), 10);
        FrontierSelection roomy =
                selector.select(
                        IDENTITY, tree.nodes(), tree.turns(), List.of(tree.rootRange()), 17);

        assertThat(tight.nodeIds()).containsExactly("n-1-4", "n-5-8");
        assertThat(roomy.nodeIds()).containsExactly("n-1-4", "n-5-6", "n-7", "n-8");
        assertThat(roomy.estimatedTokenCount()).isGreaterThan(tight.estimatedTokenCount());
    }

    @Test
    void reportsMissingAlignedRangesWithoutPublishingAPartialFrontier() {
        Tree tree = tree(1, 8);
        List<HierarchicalSummaryNode> withoutNewestHalf =
                tree.nodes().stream()
                        .filter(node -> node.endConversationMessageSequence() < 50)
                        .toList();

        FrontierSelection selection =
                selector.select(
                        IDENTITY, withoutNewestHalf, tree.turns(), List.of(tree.rootRange()), 100);

        assertThat(selection.publishable()).isFalse();
        assertThat(selection.nodeIds()).isEmpty();
        assertThat(selection.missingRanges())
                .extracting(SummaryRange::startTurn, SummaryRange::endTurn)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(5L, 8L));
    }

    @Test
    void reportsMissingParentRangesWhenExistingLeavesCannotMeetTheBudget() {
        Tree tree = tree(1, 8);
        List<HierarchicalSummaryNode> leaves =
                tree.nodes().stream().filter(node -> node.level() == 0).toList();

        FrontierSelection selection =
                selector.select(IDENTITY, leaves, tree.turns(), List.of(tree.rootRange()), 10);

        assertThat(selection.publishable()).isFalse();
        assertThat(selection.missingRanges())
                .extracting(SummaryRange::startTurn, SummaryRange::endTurn)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1L, 2L),
                        org.assertj.core.groups.Tuple.tuple(3L, 4L),
                        org.assertj.core.groups.Tuple.tuple(5L, 6L),
                        org.assertj.core.groups.Tuple.tuple(7L, 8L));
    }

    @Test
    void neverCombinesAdjacentLeavesThatAreNotGloballyAlignedSiblings() {
        List<ConversationTurn> turns = turns(3);
        List<HierarchicalSummaryNode> leaves =
                List.of(node("n-2", 0, 2, 2, null, null, 4), node("n-3", 0, 3, 3, null, null, 4));
        List<SummaryRange> targets =
                List.of(new SummaryRange(2, 2, 20, 29, 0), new SummaryRange(3, 3, 30, 39, 0));

        FrontierSelection selection = selector.select(IDENTITY, leaves, turns, targets, 4);

        assertThat(selection.publishable()).isFalse();
        assertThat(selection.missingRanges())
                .allSatisfy(
                        range ->
                                assertThat((range.startTurn() - 1L) % range.coveredTurns())
                                        .isZero());
    }

    @Test
    void reportsOnlyAlignedBuildRangesForTheRealWarmBoundary() {
        BinarySummaryTreePlanner planner =
                new BinarySummaryTreePlanner(
                        new HierarchicalContextPolicySnapshot(1, 0.75d, 0.70d, 10, 100, 1, 1));
        List<ConversationTurn> turns = turns(157);
        List<SummaryRange> targets = planner.plan(turns, 0).warmTargetRanges();
        List<HierarchicalSummaryNode> leaves =
                turns.stream()
                        .filter(turn -> turn.ordinal() >= 58 && turn.ordinal() <= 147)
                        .map(
                                turn ->
                                        node(
                                                "n-" + turn.ordinal(),
                                                0,
                                                turn.ordinal(),
                                                turn.ordinal(),
                                                null,
                                                null,
                                                4))
                        .toList();

        FrontierSelection selection = selector.select(IDENTITY, leaves, turns, targets, 100);

        assertThat(selection.publishable()).isFalse();
        assertThat(selection.missingRanges()).isNotEmpty();
        assertThat(selection.missingRanges())
                .allSatisfy(
                        range ->
                                assertThat((range.startTurn() - 1L) % range.coveredTurns())
                                        .isZero());
    }

    @Test
    void doesNotExpandOlderHistoryAfterANewerExpansionIsUnaffordable() {
        Tree tree = tree(1, 8);
        Map<String, Integer> asymmetricTokens =
                Map.of(
                        "n-1-8", 4,
                        "n-1-4", 5,
                        "n-5-8", 5,
                        "n-1-2", 3,
                        "n-3-4", 3,
                        "n-5-6", 8,
                        "n-7-8", 8);
        List<HierarchicalSummaryNode> nodes =
                tree.nodes().stream()
                        .map(
                                node ->
                                        copyWithTokens(
                                                node,
                                                asymmetricTokens.getOrDefault(
                                                        node.nodeId(), node.estimatedTokenCount())))
                        .toList();

        FrontierSelection selection =
                selector.select(IDENTITY, nodes, tree.turns(), List.of(tree.rootRange()), 15);

        assertThat(selection.nodeIds()).containsExactly("n-1-4", "n-5-8");
    }

    @Test
    void rejectsNodesFromAnotherGenerationIdentity() {
        Tree tree = tree(1, 8);
        HierarchicalSummaryNode source = tree.nodes().getFirst();
        List<HierarchicalSummaryNode> incompatibleNodes =
                List.of(
                        copyWithIdentity(source, "another-session", 1, 1, PROVIDER, MODEL),
                        copyWithIdentity(source, SESSION_ID, 2, 1, PROVIDER, MODEL),
                        copyWithIdentity(source, SESSION_ID, 1, 2, PROVIDER, MODEL),
                        copyWithIdentity(source, SESSION_ID, 1, 1, "another-provider", MODEL),
                        copyWithIdentity(source, SESSION_ID, 1, 1, PROVIDER, "another-model"));

        assertThat(incompatibleNodes)
                .allSatisfy(
                        incompatible -> {
                            List<HierarchicalSummaryNode> mixed = new ArrayList<>(tree.nodes());
                            mixed.set(0, incompatible);
                            assertThatThrownBy(
                                            () ->
                                                    selector.select(
                                                            IDENTITY,
                                                            mixed,
                                                            tree.turns(),
                                                            List.of(tree.rootRange()),
                                                            100))
                                    .isInstanceOf(IllegalArgumentException.class);
                            assertThatThrownBy(
                                            () ->
                                                    new HierarchicalInvariantValidator()
                                                            .validateFrontier(
                                                                    FrontierSelection.empty(),
                                                                    List.of(incompatible),
                                                                    List.of(),
                                                                    0,
                                                                    null,
                                                                    IDENTITY))
                                    .isInstanceOf(IllegalArgumentException.class);
                        });
    }

    @Test
    void invariantValidatorRejectsRangesBeforeColdAndTheIncompleteCurrentTurn() {
        SummaryRange beforeCold = new SummaryRange(1, 1, 10, 19, 0);
        SummaryRange first = new SummaryRange(1, 1, 10, 19, 0);
        SummaryRange gap = new SummaryRange(3, 3, 30, 39, 0);
        SummaryRange incomplete = new SummaryRange(9, 9, 90, 99, 0);
        HierarchicalInvariantValidator validator = new HierarchicalInvariantValidator();

        assertThatThrownBy(() -> validator.validateRanges(List.of(beforeCold), 1, 1, 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateRanges(List.of(first, gap), 1, 3, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateRanges(List.of(incomplete), 9, 9, 0, 9L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SummaryRange(1, 3, 10, 39, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Tree tree(long startTurn, long endTurn) {
        List<ConversationTurn> turns = new ArrayList<>(turns(Math.toIntExact(endTurn)));
        List<HierarchicalSummaryNode> nodes = new ArrayList<>();
        for (long turn = startTurn; turn <= endTurn; turn++) {
            nodes.add(node("n-" + turn, 0, turn, turn, null, null, 4));
        }

        // Every parent is immutable and points at its two chronological direct children.
        for (int level = 1; level <= 3; level++) {
            long size = 1L << level;
            int tokens = 6 - level;
            for (long start = startTurn; start <= endTurn; start += size) {
                long midpoint = start + size / 2L;
                long end = start + size - 1L;
                nodes.add(
                        node(
                                id(start, end),
                                level,
                                start,
                                end,
                                id(start, midpoint - 1L),
                                id(midpoint, end),
                                tokens));
            }
        }
        return new Tree(
                List.copyOf(turns),
                List.copyOf(nodes),
                new SummaryRange(startTurn, endTurn, startTurn * 10L, endTurn * 10L + 9L, 3));
    }

    private static List<ConversationTurn> turns(int total) {
        List<ConversationTurn> turns = new ArrayList<>();
        for (int ordinal = 1; ordinal <= total; ordinal++) {
            long startSequence = ordinal * 10L;
            turns.add(
                    new ConversationTurn(
                            ordinal,
                            startSequence,
                            startSequence + 9L,
                            true,
                            List.of(ContextMessage.user("question-" + ordinal))));
        }
        return List.copyOf(turns);
    }

    private static HierarchicalSummaryNode copyWithTokens(
            HierarchicalSummaryNode source, int tokens) {
        return new HierarchicalSummaryNode(
                source.nodeId(),
                source.conversationSessionId(),
                source.level(),
                source.coveredCompletedTurnCount(),
                source.startConversationMessageSequence(),
                source.endConversationMessageSequence(),
                source.leftChildNodeId(),
                source.rightChildNodeId(),
                source.summaryDocument(),
                tokens,
                source.summarySchemaVersion(),
                source.summaryPromptVersion(),
                source.modelProvider(),
                source.modelName());
    }

    private static HierarchicalSummaryNode copyWithIdentity(
            HierarchicalSummaryNode source,
            String sessionId,
            int schemaVersion,
            int promptVersion,
            String provider,
            String model) {
        return new HierarchicalSummaryNode(
                source.nodeId(),
                sessionId,
                source.level(),
                source.coveredCompletedTurnCount(),
                source.startConversationMessageSequence(),
                source.endConversationMessageSequence(),
                source.leftChildNodeId(),
                source.rightChildNodeId(),
                source.summaryDocument(),
                source.estimatedTokenCount(),
                schemaVersion,
                promptVersion,
                provider,
                model);
    }

    private static HierarchicalSummaryNode node(
            String id,
            int level,
            long startTurn,
            long endTurn,
            String leftChild,
            String rightChild,
            int tokens) {
        return new HierarchicalSummaryNode(
                id,
                SESSION_ID,
                level,
                endTurn - startTurn + 1L,
                startTurn * 10L,
                endTurn * 10L + 9L,
                leftChild,
                rightChild,
                SUMMARY,
                tokens,
                1,
                1,
                PROVIDER,
                MODEL);
    }

    private static String id(long start, long end) {
        return start == end ? "n-" + start : "n-" + start + "-" + end;
    }

    private record Tree(
            List<ConversationTurn> turns,
            List<HierarchicalSummaryNode> nodes,
            SummaryRange rootRange) {}
}
