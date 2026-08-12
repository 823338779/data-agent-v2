package com.datagent.agentv2.context.lsm.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.context.lsm.domain.ConversationTurn;
import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan;
import com.datagent.agentv2.context.lsm.domain.LsmContextManifest;
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
                                    new LsmPlanningInput.Policy(1, fanout, maxLevel),
                                    new LsmPlanningInput.TokenPressure(20_000, 8_000, 16_000),
                                    true);
                    LsmCompactionPlan plan = planner.plan(input);

                    validator.validatePlan(input, plan);
                    assertPublishedInvariants(plan, fanout, maxLevel);
                    manifest = plan.proposedManifest();
                    activeSegments = plan.projectedSegments();
                    nextPublish = sequence + 91 + random.nextInt(29);
                }
            }
        }
    }

    private static void assertPublishedInvariants(
            LsmCompactionPlan plan, int fanout, int maxLevel) {
        assertThat(plan.hotBoundary().completedTurnCount()).isEqualTo(10);
        assertThat(plan.proposedManifest().warmTurnCount()).isBetween(100L, 109L);
        assertThat(plan.projectedSegments())
                .filteredOn(segment -> segment.kind() == LsmSummarySegment.Kind.ROOT)
                .hasSizeLessThanOrEqualTo(1);
        for (int level = 0; level < maxLevel; level++) {
            int checkedLevel = level;
            assertThat(plan.projectedSegments())
                    .filteredOn(
                            segment ->
                                    segment.kind() == LsmSummarySegment.Kind.STANDARD
                                            && segment.level() == checkedLevel)
                    .hasSizeLessThanOrEqualTo(fanout - 1);
        }
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
