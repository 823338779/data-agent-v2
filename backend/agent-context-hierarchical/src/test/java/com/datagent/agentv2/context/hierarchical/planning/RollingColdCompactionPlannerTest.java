package com.datagent.agentv2.context.hierarchical.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.context.common.summary.EventSummaryCoverage;
import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCold;
import com.datagent.agentv2.context.hierarchical.domain.RollingColdCompactionPlan.TurnRange;
import com.datagent.agentv2.conversation.context.ContextMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RollingColdCompactionPlannerTest {
    private final RollingColdCompactionPlanner planner =
            new RollingColdCompactionPlanner(new ConversationTokenEstimator());

    @Test
    void firstCompactionProtectsExactlyTheLastTenCompletedTurns() {
        var plan = planner.plan(240, null, turns(120), 2_000, 4_096);

        assertThat(plan.sourceColdId()).isNull();
        assertThat(plan.rawSourceRange()).isEqualTo(new TurnRange(1, 110));
        assertThat(plan.protectedRawRange()).isEqualTo(new TurnRange(111, 120));
        assertThat(plan.batches()).isNotEmpty();
    }

    @Test
    void incrementalCompactionStartsImmediatelyAfterThePublishedCold() {
        var plan = planner.plan(290, cold(110), turns(145), 2_000, 4_096);

        assertThat(plan.sourceColdId()).isEqualTo("cold-110");
        assertThat(plan.rawSourceRange()).isEqualTo(new TurnRange(111, 135));
        assertThat(plan.protectedRawRange()).isEqualTo(new TurnRange(136, 145));
    }

    @Test
    void noNewRawStillAllowsPureColdRecompactionForASmallerBudget() {
        var plan = planner.plan(290, cold(135), turns(145), 800, 4_096);

        assertThat(plan.rawSourceRange()).isEqualTo(TurnRange.EMPTY);
        assertThat(plan.targetCoverage()).isEqualTo(new TurnRange(1, 135));
        assertThat(plan.historyMemoryBudget()).isEqualTo(800);
    }

    private static List<ConversationTurn> turns(int count) {
        List<ConversationTurn> turns = new ArrayList<>();
        for (int ordinal = 1; ordinal <= count; ordinal++) {
            turns.add(
                    new ConversationTurn(
                            ordinal,
                            ordinal * 2L - 1,
                            ordinal * 2L,
                            true,
                            List.of(
                                    ContextMessage.user("u" + ordinal),
                                    ContextMessage.assistant("a" + ordinal, null))));
        }
        return turns;
    }

    private static HierarchicalCold cold(long endTurn) {
        return new HierarchicalCold(
                "cold-" + endTurn,
                "session",
                new EventSummaryCoverage(1, endTurn, 1, endTurn * 2),
                "{}",
                100,
                80,
                100,
                "provider",
                "model",
                "0".repeat(64));
    }
}
