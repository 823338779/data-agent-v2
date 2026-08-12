package com.datagent.agentv2.context.lsm.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.context.lsm.domain.ConversationTurn;
import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan;
import com.datagent.agentv2.context.lsm.domain.LsmContextManifest;
import com.datagent.agentv2.context.lsm.domain.LsmPlanningInput;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment.Kind;
import com.datagent.agentv2.conversation.context.ContextMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LsmCompactionPlannerTest {

    private static final int LEVEL_ZERO = 0;
    private static final int SEGMENT_TOKENS = 100;
    private static final String ROOT_ID = "root";
    private static final String OLD_ROOT_ID = "old-root";
    private static final String LEVEL_ID_PREFIX = "l";
    private static final String QUESTION_PREFIX = "question-";
    private static final String ANSWER_PREFIX = "answer-";
    private static final String FIRST_HIGH_ID = "first-high";
    private static final String LOWER_LEVEL_ID = "lower-level";
    private static final String LATER_HIGH_ID = "later-high";
    private static final String MIXED_LEVEL_FOUR_ID = "mixed-l4";
    private static final String MIXED_LEVEL_THREE_ID = "mixed-l3";

    private final LsmCompactionPlanner planner = new LsmCompactionPlanner();

    @Test
    void fiveAdjacentL0SegmentsPromoteOnceWhenFanoutIsFive() {
        LsmCompactionPlan plan = planner.plan(input(segments(5, LEVEL_ZERO), 5, 4));

        assertThat(plan.merges())
                .singleElement()
                .satisfies(
                        merge -> {
                            assertThat(merge.sourceIds()).hasSize(5);
                            assertThat(merge.targetLevel()).isEqualTo(1);
                        });
    }

    @Test
    void twoNodesNeverMergeForAnySupportedFanout() {
        for (int fanout : List.of(3, 5, 10)) {
            LsmCompactionPlan plan = planner.plan(input(segments(2, LEVEL_ZERO), fanout, 4));

            assertThat(plan.merges()).as("fanout %s", fanout).isEmpty();
        }
    }

    @Test
    void createsAndThenRollsTheConfiguredTopLevelRoot() {
        for (int maxLevel : List.of(3, 4, 5)) {
            List<LsmSummarySegment> topChildren = segments(3, maxLevel - 1);
            LsmCompactionPlan first = planner.plan(input(topChildren, 3, maxLevel));

            assertThat(first.merges())
                    .singleElement()
                    .satisfies(
                            operation -> {
                                assertThat(operation.targetKind()).isEqualTo(Kind.ROOT);
                                assertThat(operation.targetLevel()).isEqualTo(maxLevel);
                                assertThat(operation.sourceIds()).hasSize(3);
                            });

            LsmSummarySegment root =
                    segment(
                            ROOT_ID,
                            Kind.ROOT,
                            maxLevel,
                            1,
                            30,
                            500,
                            topChildren.stream().map(LsmSummarySegment::segmentId).toList());
            List<LsmSummarySegment> nextChildren = shiftedSegments(3, maxLevel - 1, 31);
            LsmCompactionPlan rolled = planner.plan(inputWithRoot(root, nextChildren, 3, maxLevel));

            assertThat(rolled.merges())
                    .singleElement()
                    .satisfies(
                            operation -> {
                                assertThat(operation.targetKind()).isEqualTo(Kind.ROOT);
                                assertThat(operation.sourceIds())
                                        .containsExactly(
                                                ROOT_ID,
                                                nextChildren.get(0).segmentId(),
                                                nextChildren.get(1).segmentId(),
                                                nextChildren.get(2).segmentId());
                            });
        }
    }

    @Test
    void hardPressureCanSealAForcedPartialL0WithoutTouchingHot() {
        List<ConversationTurn> turns = completedTurns(17);
        LsmPlanningInput input =
                new LsmPlanningInput(
                        turns,
                        LsmContextManifest.empty(),
                        List.of(),
                        new LsmPlanningInput.Policy(1, 5, 4),
                        new LsmPlanningInput.TokenPressure(20_000, 8_000, 16_000),
                        true);

        LsmCompactionPlan plan = planner.plan(input);

        assertThat(plan.hotBoundary().startTurnSequence()).isEqualTo(8);
        assertThat(plan.seals())
                .singleElement()
                .satisfies(
                        operation -> {
                            assertThat(operation.partialSegment()).isTrue();
                            assertThat(operation.coverage().startTurnSequence()).isEqualTo(1);
                            assertThat(operation.coverage().endTurnSequence()).isEqualTo(7);
                        });
    }

    @Test
    void reconcilesLowerMaxLevelIntoOneRoot() {
        LsmSummarySegment oldRoot = segment(OLD_ROOT_ID, Kind.ROOT, 5, 1, 30, 500, List.of());
        List<LsmSummarySegment> tail = shiftedSegments(3, 3, 31);

        LsmCompactionPlan plan = planner.plan(inputWithRoot(oldRoot, tail, 3, 3));

        assertThat(plan.merges())
                .singleElement()
                .satisfies(
                        operation -> {
                            assertThat(operation.targetKind()).isEqualTo(Kind.ROOT);
                            assertThat(operation.targetLevel()).isEqualTo(3);
                            assertThat(operation.sourceIds()).startsWith(OLD_ROOT_ID);
                        });
    }

    @Test
    void lowerMaxLevelReconcilesOldRootAndTwoHighTailNodesAsThreeSources() {
        LsmSummarySegment oldRoot = segment(OLD_ROOT_ID, Kind.ROOT, 5, 1, 30, 500, List.of());
        List<LsmSummarySegment> highTailNodes = shiftedSegments(2, 4, 31);

        LsmCompactionPlan plan = planner.plan(inputWithRoot(oldRoot, highTailNodes, 3, 3));

        assertThat(plan.merges())
                .singleElement()
                .satisfies(
                        operation -> {
                            assertThat(operation.type())
                                    .isEqualTo(
                                            LsmCompactionPlan.OperationType
                                                    .RECONCILE_CONFIGURATION);
                            assertThat(operation.targetKind()).isEqualTo(Kind.ROOT);
                            assertThat(operation.targetLevel()).isEqualTo(3);
                            assertThat(operation.sourceIds())
                                    .containsExactly(
                                            OLD_ROOT_ID,
                                            highTailNodes.get(0).segmentId(),
                                            highTailNodes.get(1).segmentId());
                        });
        assertThat(plan.projectedSegments()).singleElement();
    }

    @Test
    void configurationReconciliationConsumesExactlyFanoutSourcesAndRetainsExtraTail() {
        LsmSummarySegment oldRoot = segment(OLD_ROOT_ID, Kind.ROOT, 5, 1, 30, 500, List.of());
        List<LsmSummarySegment> highTailNodes = shiftedSegments(3, 4, 31);

        LsmCompactionPlan plan = planner.plan(inputWithRoot(oldRoot, highTailNodes, 3, 3));

        assertThat(plan.merges())
                .singleElement()
                .satisfies(
                        operation -> {
                            assertThat(operation.sourceIds())
                                    .containsExactly(
                                            OLD_ROOT_ID,
                                            highTailNodes.get(0).segmentId(),
                                            highTailNodes.get(1).segmentId());
                            assertThat(operation.sourceIds()).hasSize(3);
                        });
        assertThat(plan.projectedSegments())
                .satisfiesExactly(
                        root -> {
                            assertThat(root.kind()).isEqualTo(Kind.ROOT);
                            assertThat(root.coverage())
                                    .isEqualTo(new LsmSummarySegment.Coverage(1, 50));
                        },
                        tail -> assertThat(tail).isEqualTo(highTailNodes.get(2)));
        LsmInvariantValidator validator = new LsmInvariantValidator();
        validator.assertContinuous(
                plan.projectedSegments().stream().map(LsmSummarySegment::coverage).toList());
        validator.validatePlan(inputWithRoot(oldRoot, highTailNodes, 3, 3), plan);
    }

    @Test
    void configurationReconciliationDoesNotSkipAnIneligibleTailNode() {
        LsmSummarySegment oldRoot = segment(OLD_ROOT_ID, Kind.ROOT, 5, 1, 30, 500, List.of());
        LsmSummarySegment firstHigh =
                segment(FIRST_HIGH_ID, Kind.STANDARD, 4, 31, 40, 100, List.of());
        LsmSummarySegment lowerLevel =
                segment(LOWER_LEVEL_ID, Kind.STANDARD, 2, 41, 50, 100, List.of());
        LsmSummarySegment laterHigh =
                segment(LATER_HIGH_ID, Kind.STANDARD, 4, 51, 60, 100, List.of());
        List<LsmSummarySegment> tail = List.of(firstHigh, lowerLevel, laterHigh);

        LsmCompactionPlan plan = planner.plan(inputWithRoot(oldRoot, tail, 3, 3));

        assertThat(plan.merges()).isEmpty();
        assertThat(plan.projectedSegments())
                .containsExactly(oldRoot, firstHigh, lowerLevel, laterHigh);
    }

    @Test
    void configurationReconciliationRequiresSameLevelTailPrefix() {
        LsmSummarySegment oldRoot = segment(OLD_ROOT_ID, Kind.ROOT, 5, 1, 30, 500, List.of());
        LsmSummarySegment levelFour =
                segment(MIXED_LEVEL_FOUR_ID, Kind.STANDARD, 4, 31, 40, 100, List.of());
        LsmSummarySegment levelThree =
                segment(MIXED_LEVEL_THREE_ID, Kind.STANDARD, 3, 41, 50, 100, List.of());

        LsmCompactionPlan plan =
                planner.plan(inputWithRoot(oldRoot, List.of(levelFour, levelThree), 3, 3));

        assertThat(plan.merges()).isEmpty();
        assertThat(plan.projectedSegments()).containsExactly(oldRoot, levelFour, levelThree);
    }

    @Test
    void configurationReconciliationRepeatsUntilFiveOldFanoutTailNodesAreLegal() {
        LsmSummarySegment oldRoot = segment(OLD_ROOT_ID, Kind.ROOT, 5, 1, 30, 500, List.of());
        List<LsmSummarySegment> highTailNodes = shiftedSegments(5, 4, 31);

        LsmCompactionPlan plan = planner.plan(inputWithRoot(oldRoot, highTailNodes, 3, 3));

        assertThat(plan.merges()).hasSize(2);
        assertThat(plan.merges())
                .allSatisfy(operation -> assertThat(operation.sourceIds()).hasSize(3));
        assertThat(plan.merges().get(0).sourceIds())
                .containsExactly(
                        OLD_ROOT_ID,
                        highTailNodes.get(0).segmentId(),
                        highTailNodes.get(1).segmentId());
        assertThat(plan.merges().get(1).sourceIds())
                .containsExactly(
                        plan.merges().get(0).targetId(),
                        highTailNodes.get(2).segmentId(),
                        highTailNodes.get(3).segmentId());
        assertThat(plan.projectedSegments())
                .satisfiesExactly(
                        root -> {
                            assertThat(root.kind()).isEqualTo(Kind.ROOT);
                            assertThat(root.sourceSegmentIds())
                                    .doesNotContain(highTailNodes.get(4).segmentId());
                        },
                        tail -> assertThat(tail).isEqualTo(highTailNodes.get(4)));
    }

    @Test
    void configurationReconciliationSupportsMultipleDeterministicBatches() {
        LsmSummarySegment oldRoot = segment(OLD_ROOT_ID, Kind.ROOT, 5, 1, 30, 500, List.of());
        List<LsmSummarySegment> highTailNodes = shiftedSegments(8, 4, 31);

        LsmCompactionPlan plan = planner.plan(inputWithRoot(oldRoot, highTailNodes, 3, 3));

        assertThat(plan.merges()).hasSize(4);
        assertThat(plan.merges())
                .allSatisfy(operation -> assertThat(operation.sourceIds()).hasSize(3));
        for (int index = 1; index < plan.merges().size(); index++) {
            assertThat(plan.merges().get(index).sourceIds().getFirst())
                    .isEqualTo(plan.merges().get(index - 1).targetId());
        }
        assertThat(plan.projectedSegments())
                .singleElement()
                .satisfies(root -> assertThat(root.kind()).isEqualTo(Kind.ROOT));
        new LsmInvariantValidator().validatePlan(inputWithRoot(oldRoot, highTailNodes, 3, 3), plan);
    }

    @Test
    void warmMovesOnlyInTenTurnBatchesAndStaysBetweenOneHundredAndOneHundredNine() {
        LsmPlanningInput input =
                new LsmPlanningInput(
                        completedTurns(127),
                        LsmContextManifest.empty(),
                        List.of(),
                        new LsmPlanningInput.Policy(1, 5, 4),
                        new LsmPlanningInput.TokenPressure(20_000, 8_000, 16_000),
                        true);

        LsmCompactionPlan plan = planner.plan(input);

        assertThat(plan.proposedManifest().warmTurnCount()).isEqualTo(107);
        assertThat(plan.proposedManifest().warmStartTurnSequence()).isEqualTo(11);
        assertThat(plan.operations())
                .filteredOn(
                        operation -> operation.type() == LsmCompactionPlan.OperationType.TRIM_WARM)
                .singleElement()
                .satisfies(
                        operation -> {
                            assertThat(operation.coverage().startTurnSequence()).isEqualTo(1);
                            assertThat(operation.coverage().endTurnSequence()).isEqualTo(10);
                        });
    }

    @Test
    void majorCompactionRequiresThreeColdNodesAndTwentyPercentReduction() {
        List<LsmSummarySegment> cold = segments(3, LEVEL_ZERO);
        LsmPlanningInput.TokenPressure pressure =
                new LsmPlanningInput.TokenPressure(12_000, 8_000, 16_000);
        LsmPlanningInput input = input(cold, 5, 4, pressure);

        LsmCompactionPlan plan = planner.plan(input);

        assertThat(plan.merges())
                .singleElement()
                .satisfies(
                        operation -> {
                            assertThat(operation.type())
                                    .isEqualTo(LsmCompactionPlan.OperationType.MAJOR_COMPACTION);
                            assertThat(operation.sourceIds()).hasSize(3);
                        });

        List<LsmSummarySegment> onlyTwo = segments(2, LEVEL_ZERO);
        assertThat(planner.plan(input(onlyTwo, 5, 4, pressure)).merges()).isEmpty();
    }

    private static LsmPlanningInput input(
            List<LsmSummarySegment> segments, int fanout, int maxLevel) {
        return input(
                segments,
                fanout,
                maxLevel,
                new LsmPlanningInput.TokenPressure(7_000, 8_000, 16_000));
    }

    private static LsmPlanningInput input(
            List<LsmSummarySegment> segments,
            int fanout,
            int maxLevel,
            LsmPlanningInput.TokenPressure pressure) {
        return new LsmPlanningInput(
                List.of(),
                new LsmContextManifest(
                        null,
                        segments.stream().map(LsmSummarySegment::segmentId).toList(),
                        null,
                        null,
                        null,
                        null,
                        0,
                        fanout,
                        maxLevel),
                segments,
                new LsmPlanningInput.Policy(1, fanout, maxLevel),
                pressure,
                false);
    }

    private static LsmPlanningInput inputWithRoot(
            LsmSummarySegment root, List<LsmSummarySegment> tail, int fanout, int maxLevel) {
        List<LsmSummarySegment> all = new ArrayList<>();
        all.add(root);
        all.addAll(tail);
        return new LsmPlanningInput(
                List.of(),
                new LsmContextManifest(
                        root.segmentId(),
                        all.stream().map(LsmSummarySegment::segmentId).toList(),
                        null,
                        null,
                        null,
                        null,
                        0,
                        fanout,
                        root.level()),
                all,
                new LsmPlanningInput.Policy(2, fanout, maxLevel),
                new LsmPlanningInput.TokenPressure(7_000, 8_000, 16_000),
                false);
    }

    private static List<LsmSummarySegment> segments(int count, int level) {
        return shiftedSegments(count, level, 1);
    }

    private static List<LsmSummarySegment> shiftedSegments(int count, int level, long start) {
        long width = 10L;
        return IntStream.range(0, count)
                .mapToObj(
                        index -> {
                            long segmentStart = start + index * width;
                            return segment(
                                    LEVEL_ID_PREFIX + level + "-" + segmentStart,
                                    Kind.STANDARD,
                                    level,
                                    segmentStart,
                                    segmentStart + width - 1,
                                    SEGMENT_TOKENS,
                                    List.of());
                        })
                .toList();
    }

    private static LsmSummarySegment segment(
            String id,
            Kind kind,
            int level,
            long start,
            long end,
            int tokens,
            List<String> sourceIds) {
        return new LsmSummarySegment(
                id,
                kind,
                level,
                new LsmSummarySegment.Coverage(start, end),
                tokens,
                sourceIds,
                false);
    }

    private static List<ConversationTurn> completedTurns(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(
                        sequence ->
                                new ConversationTurn(
                                        sequence,
                                        List.of(
                                                ContextMessage.user(QUESTION_PREFIX + sequence),
                                                ContextMessage.assistant(
                                                        ANSWER_PREFIX + sequence, null)),
                                        true))
                .toList();
    }
}
