package com.datagent.agentv2.context.lsm.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.context.lsm.domain.ConversationTurn;
import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan;
import com.datagent.agentv2.context.lsm.domain.LsmContextManifest;
import com.datagent.agentv2.context.lsm.domain.LsmContextPolicySnapshot;
import com.datagent.agentv2.context.lsm.domain.LsmPlanningInput;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment;
import com.datagent.agentv2.conversation.context.ContextMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LsmPlannerStateMachineTest {

    private static final long RANDOM_SEED = 6_805L;
    private static final int SIMULATED_TURNS = 10_000;
    private static final String QUESTION_PREFIX = "q-";
    private static final String ANSWER_PREFIX = "a-";
    private static final String RETRY_SEGMENT_PREFIX = "retry-l0-";

    private final LsmCompactionPlanner planner = new LsmCompactionPlanner();
    private final LsmInvariantValidator validator = new LsmInvariantValidator();

    @Test
    void fixedSeedSimulationPreservesPublishedManifestInvariantsAcrossPolicies() {
        Random random = new Random(RANDOM_SEED);

        for (int fanout : List.of(3, 5, 10)) {
            for (int maxLevel : List.of(3, 4, 5)) {
                List<ConversationTurn> turns = new ArrayList<>();
                LsmContextManifest manifest = LsmContextManifest.empty();
                List<LsmSummarySegment> activeSegments = List.of();
                int nextPublish = 110;

                // 固定种子改变发布间隔，模拟新增问答在两次 compaction 之间形成 pending raw。
                for (int sequence = 1; sequence <= SIMULATED_TURNS; sequence++) {
                    turns.add(completedTurn(sequence));
                    if (sequence < nextPublish && sequence != SIMULATED_TURNS) {
                        continue;
                    }
                    LsmPlanningInput input =
                            new LsmPlanningInput(
                                    turns,
                                    manifest,
                                    activeSegments,
                                    new LsmContextPolicySnapshot(
                                            1, fanout, maxLevel, 10, 75, 65, 10),
                                    new LsmPlanningInput.TokenPressure(20_000, 8_000, 16_000),
                                    true);
                    LsmCompactionPlan plan = planner.plan(input);

                    validator.validatePlan(input, plan);
                    assertPublishedInvariants(plan, fanout, maxLevel);
                    assertCompleteBoundary(plan);
                    manifest = plan.proposedManifest();
                    activeSegments = plan.projectedSegments();
                    nextPublish = sequence + 91 + random.nextInt(29);
                }
            }
        }
    }

    @Test
    void validatorRejectsPublishedStateWithMissingTailCoverage() {
        List<ConversationTurn> turns = completedTurns(130);
        LsmPlanningInput input =
                new LsmPlanningInput(
                        turns,
                        LsmContextManifest.empty(),
                        List.of(),
                        new LsmContextPolicySnapshot(1, 5, 4, 10, 75, 65, 10),
                        new LsmPlanningInput.TokenPressure(20_000, 8_000, 16_000),
                        true);
        LsmCompactionPlan valid = planner.plan(input);
        List<LsmSummarySegment> missingTail =
                valid.projectedSegments().subList(0, valid.projectedSegments().size() - 1);
        LsmContextManifest validManifest = valid.proposedManifest();
        LsmContextManifest missingTailManifest =
                new LsmContextManifest(
                        validManifest.activeRootSegmentId(),
                        missingTail.stream().map(LsmSummarySegment::segmentId).toList(),
                        null,
                        null,
                        validManifest.generation(),
                        validManifest.effectiveFanout(),
                        validManifest.effectiveMaxLevel());
        LsmCompactionPlan invalid =
                new LsmCompactionPlan(
                        valid.hotBoundary(), valid.operations(), missingTailManifest, missingTail);

        assertThatThrownBy(() -> validator.validatePlan(input, invalid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Hot boundary");
    }

    @Test
    void pendingRawContinuouslyBridgesActiveSegmentsToHotBoundary() {
        LsmPlanningInput input =
                new LsmPlanningInput(
                        completedTurns(117),
                        LsmContextManifest.empty(),
                        List.of(),
                        new LsmContextPolicySnapshot(1, 5, 4, 10, 75, 65, 10),
                        new LsmPlanningInput.TokenPressure(12_000, 8_000, 16_000),
                        false);

        LsmCompactionPlan plan = planner.plan(input);

        assertThat(plan.proposedManifest().pendingRawStartTurnSequence()).isEqualTo(101L);
        assertThat(plan.proposedManifest().pendingRawEndTurnSequence()).isEqualTo(107L);
        assertCompleteBoundary(plan);
        validator.validatePlan(input, plan);
    }

    @Test
    void retryAndTakeoverReuseFrozenPolicyAndProduceTheSamePlan() {
        List<LsmSummarySegment> segments = fiveL0Segments();
        LsmContextPolicySnapshot frozenPolicy =
                new LsmContextPolicySnapshot(7, 5, 4, 10, 75, 65, 10);
        LsmPlanningInput initialInput = planningInput(segments, frozenPolicy);

        LsmCompactionPlan initial = planner.plan(initialInput);
        LsmContextPolicySnapshot changedServicePolicy =
                new LsmContextPolicySnapshot(8, 3, 3, 10, 75, 65, 10);
        LsmCompactionPlan servicePolicyPlan =
                planner.plan(planningInput(segments, changedServicePolicy));
        LsmCompactionPlan retry = planner.plan(planningInput(segments, frozenPolicy));
        LsmCompactionPlan takeover = planner.plan(planningInput(segments, frozenPolicy));

        assertThat(servicePolicyPlan).isNotEqualTo(initial);
        assertThat(retry).isEqualTo(initial);
        assertThat(takeover).isEqualTo(initial);
    }

    private static void assertPublishedInvariants(
            LsmCompactionPlan plan, int fanout, int maxLevel) {
        assertThat(plan.hotBoundary().completedTurnCount()).isEqualTo(10);
        assertThat(plan.proposedManifest().activeSegmentIds())
                .containsExactlyElementsOf(
                        plan.projectedSegments().stream()
                                .map(LsmSummarySegment::segmentId)
                                .toList());
        assertThat(plan.projectedSegments())
                .filteredOn(segment -> segment.kind() == LsmSummarySegment.SegmentKind.ROOT)
                .hasSizeLessThanOrEqualTo(1);
        for (int level = 0; level < maxLevel; level++) {
            int checkedLevel = level;
            assertThat(plan.projectedSegments())
                    .filteredOn(
                            segment ->
                                    segment.kind() == LsmSummarySegment.SegmentKind.STANDARD
                                            && segment.level() == checkedLevel)
                    .hasSizeLessThanOrEqualTo(fanout - 1);
        }
    }

    private static void assertCompleteBoundary(LsmCompactionPlan plan) {
        long hotPredecessor = plan.hotBoundary().startTurnSequence() - 1L;
        long activeEnd =
                plan.projectedSegments().isEmpty()
                        ? 0L
                        : plan.projectedSegments().getLast().coverage().endTurnSequence();
        LsmContextManifest manifest = plan.proposedManifest();
        if (manifest.pendingRawStartTurnSequence() == null) {
            assertThat(activeEnd).isEqualTo(hotPredecessor);
        } else {
            assertThat(manifest.pendingRawStartTurnSequence()).isEqualTo(activeEnd + 1L);
            assertThat(manifest.pendingRawEndTurnSequence()).isEqualTo(hotPredecessor);
        }
    }

    private static LsmPlanningInput planningInput(
            List<LsmSummarySegment> segments, LsmContextPolicySnapshot policy) {
        return new LsmPlanningInput(
                List.of(),
                new LsmContextManifest(
                        null,
                        segments.stream().map(LsmSummarySegment::segmentId).toList(),
                        null,
                        null,
                        4,
                        policy.fanout(),
                        policy.maxLevel()),
                segments,
                policy,
                new LsmPlanningInput.TokenPressure(7_000, 8_000, 16_000),
                false);
    }

    private static List<LsmSummarySegment> fiveL0Segments() {
        List<LsmSummarySegment> segments = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            long start = index * 10L + 1L;
            segments.add(
                    new LsmSummarySegment(
                            RETRY_SEGMENT_PREFIX + index,
                            LsmSummarySegment.SegmentKind.STANDARD,
                            0,
                            new LsmSummarySegment.Coverage(start, start + 9L),
                            100,
                            List.of(),
                            false));
        }
        return List.copyOf(segments);
    }

    private static List<ConversationTurn> completedTurns(int count) {
        List<ConversationTurn> turns = new ArrayList<>();
        for (int sequence = 1; sequence <= count; sequence++) {
            turns.add(completedTurn(sequence));
        }
        return List.copyOf(turns);
    }

    private static ConversationTurn completedTurn(int sequence) {
        return new ConversationTurn(
                sequence,
                List.of(
                        ContextMessage.user(QUESTION_PREFIX + sequence),
                        ContextMessage.assistant(ANSWER_PREFIX + sequence, null)),
                true);
    }
}
