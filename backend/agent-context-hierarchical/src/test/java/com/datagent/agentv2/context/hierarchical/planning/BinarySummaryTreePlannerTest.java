package com.datagent.agentv2.context.hierarchical.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionPlan;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.domain.SummaryRange;
import com.datagent.agentv2.conversation.context.ContextBudget;
import com.datagent.agentv2.conversation.context.ContextMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BinarySummaryTreePlannerTest {

    private final BinarySummaryTreePlanner planner =
            new BinarySummaryTreePlanner(
                    new HierarchicalContextPolicySnapshot(1, 0.75d, 0.70d, 10, 100, 1, 1));

    @Test
    void buildsParentsOnlyFromAdjacentSameLevelChildren() {
        SummaryRange first = new SummaryRange(1, 1, 10, 19, 0);
        SummaryRange second = new SummaryRange(2, 2, 20, 29, 0);

        SummaryRange parent = planner.parent(first, second);

        assertThat(parent).isEqualTo(new SummaryRange(1, 2, 10, 29, 1));
        assertThat(planner.parent(parent, new SummaryRange(3, 4, 30, 49, 1)))
                .isEqualTo(new SummaryRange(1, 4, 10, 49, 2));
        assertThatThrownBy(() -> planner.parent(first, new SummaryRange(3, 3, 30, 39, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner.parent(first, new SummaryRange(3, 4, 30, 49, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void derivesTheMaximumUsefulLevelAndAlignedWarmRanges() {
        HierarchicalCompactionPlan plan = planner.plan(turns(157, false), 0);

        assertThat(planner.maximumUsefulLevel(plan.warmTarget().coveredTurns())).isEqualTo(6);
        assertThat(plan.warmTargetRanges())
                .allSatisfy(
                        range -> {
                            assertThat(Long.bitCount(range.coveredTurns())).isEqualTo(1);
                            assertThat((range.startTurn() - 1) % range.coveredTurns()).isZero();
                        });
        assertThat(plan.warmTargetRanges().stream().mapToLong(SummaryRange::coveredTurns).sum())
                .isEqualTo(90);
    }

    @Test
    void plansColdWarmAndHotBoundariesForOneHundredFiftySevenCompletedTurns() {
        HierarchicalCompactionPlan plan = planner.plan(turns(157, false), 0);

        assertThat(plan.coldTarget()).isEqualTo(new HierarchicalCompactionPlan.TurnRange(1, 57));
        assertThat(plan.warmTarget()).isEqualTo(new HierarchicalCompactionPlan.TurnRange(58, 147));
        assertThat(plan.hotTarget()).isEqualTo(new HierarchicalCompactionPlan.TurnRange(148, 157));
        assertThat(plan.coldPromotionRanges().getFirst().startTurn()).isEqualTo(1);
        assertThat(plan.coldPromotionRanges().getLast().endTurn()).isEqualTo(57);
    }

    @Test
    void naturallyShortensTierBoundariesAtTenAndOneHundredTurns() {
        HierarchicalCompactionPlan nine = planner.plan(turns(9, false), 0);
        HierarchicalCompactionPlan ten = planner.plan(turns(10, false), 0);
        HierarchicalCompactionPlan hundred = planner.plan(turns(100, false), 0);

        assertThat(nine.coldTarget().isEmpty()).isTrue();
        assertThat(nine.warmTarget().isEmpty()).isTrue();
        assertThat(nine.hotTarget()).isEqualTo(new HierarchicalCompactionPlan.TurnRange(1, 9));
        assertThat(ten.hotTarget()).isEqualTo(new HierarchicalCompactionPlan.TurnRange(1, 10));
        assertThat(hundred.coldTarget().isEmpty()).isTrue();
        assertThat(hundred.warmTarget()).isEqualTo(new HierarchicalCompactionPlan.TurnRange(1, 90));
        assertThat(hundred.hotTarget())
                .isEqualTo(new HierarchicalCompactionPlan.TurnRange(91, 100));
    }

    @Test
    void excludesIncompleteCurrentTurnAndRespectsAnAdvancedColdPointer() {
        HierarchicalCompactionPlan plan = planner.plan(turns(158, true), 32);
        HierarchicalCompactionPlan emergency =
                planner.plan(
                        turns(158, true), 32, 57_345, new ContextBudget(65_536, 4_096, 4_096, 0));

        assertThat(plan.completedTurnBoundary()).isEqualTo(157);
        assertThat(plan.currentIncompleteTurn()).contains(158L);
        assertThat(plan.coldPromotionRanges().getFirst().startTurn()).isEqualTo(33);
        assertThat(plan.coldPromotionRanges().getLast().endTurn()).isEqualTo(57);
        assertThat(plan.warmTargetRanges())
                .noneSatisfy(range -> assertThat(range.endTurn()).isEqualTo(158));
        assertThat(emergency.hotDegradationRanges().getLast().endTurn()).isEqualTo(157L);
    }

    @Test
    void exposesOldestFirstHotCandidatesOnlyAfterTheHardLimit() {
        ContextBudget budget = new ContextBudget(65_536, 4_096, 4_096, 0);
        HierarchicalCompactionPlan atLimit = planner.plan(turns(157, false), 0, 57_344, budget);
        HierarchicalCompactionPlan aboveLimit = planner.plan(turns(157, false), 0, 57_345, budget);

        assertThat(atLimit.hotDegradationRanges()).isEmpty();
        assertThat(aboveLimit.hotDegradationRanges())
                .extracting(SummaryRange::startTurn)
                .containsExactly(148L, 149L, 150L, 151L, 152L, 153L, 154L, 155L, 156L, 157L);
        assertThat(aboveLimit.hotDegradationRanges().getFirst().startMessageSequence())
                .isEqualTo(1_480L);
    }

    private static List<ConversationTurn> turns(int total, boolean lastIncomplete) {
        List<ConversationTurn> turns = new ArrayList<>();
        for (int ordinal = 1; ordinal <= total; ordinal++) {
            long startSequence = ordinal * 10L;
            boolean completed = !lastIncomplete || ordinal < total;
            turns.add(
                    new ConversationTurn(
                            ordinal,
                            startSequence,
                            startSequence + 9L,
                            completed,
                            List.of(ContextMessage.user("question-" + ordinal))));
        }
        return turns;
    }
}
