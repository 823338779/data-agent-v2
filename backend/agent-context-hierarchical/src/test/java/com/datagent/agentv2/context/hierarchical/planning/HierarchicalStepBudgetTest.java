package com.datagent.agentv2.context.hierarchical.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.context.hierarchical.configuration.StepCompactionProperties;
import com.datagent.agentv2.context.hierarchical.domain.ContextStepCursor;
import com.datagent.agentv2.context.hierarchical.domain.ContextStepGroup;
import com.datagent.agentv2.conversation.context.ContextBudget;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextPreparationException;
import java.util.List;
import org.junit.jupiter.api.Test;

class HierarchicalStepBudgetTest {
    private static final String HASH = "0".repeat(64);
    private static final String CONTENT = "a".repeat(2000);
    private final StepCompactionProperties properties = new StepCompactionProperties();
    private final HierarchicalStepBudgetPlanner planner =
            new HierarchicalStepBudgetPlanner(new ConversationTokenEstimator(), properties);

    @Test
    void usesHardInputBudgetAsRatioBase() {
        var budget = new ContextBudget(10000, 2000, 1000, 0);
        assertThat(properties.triggerTokens(budget)).isEqualTo(4900);
        assertThat(properties.targetTokens(budget)).isEqualTo(3850);
    }

    @Test
    void recentTwoGroupsAreSoftPreference() {
        var plan =
                planner.plan(
                        List.of(group(1), group(2), group(3)),
                        ContextStepCursor.ORIGIN,
                        7000,
                        3000);
        assertThat(plan.prefix()).isNotEmpty();
        assertThat(plan.suffix()).hasSizeLessThan(2);
        assertThat(plan.summaryBudget()).isPositive();
    }

    @Test
    void necessaryInputAboveTargetButBelowHardCanContinue() {
        var plan = planner.plan(List.of(group(1), group(2)), ContextStepCursor.ORIGIN, 7000, 5000);
        assertThat(plan.summaryBudget()).isPositive().isLessThanOrEqualTo(2000);
        assertThat(plan.suffix()).isEmpty();
    }

    @Test
    void necessaryInputAboveHardFailsBeforeSummary() {
        assertThatThrownBy(
                        () -> planner.plan(List.of(group(1)), ContextStepCursor.ORIGIN, 7000, 7001))
                .isInstanceOf(ContextPreparationException.class);
    }

    private ContextStepGroup group(int index) {
        var from =
                index == 1
                        ? ContextStepCursor.ORIGIN
                        : new ContextStepCursor(
                                2, ContextStepCursor.PositionKind.INVOCATION_END, index - 1);
        return new ContextStepGroup(
                from,
                new ContextStepCursor(2, ContextStepCursor.PositionKind.INVOCATION_END, index),
                List.of(ContextMessage.assistant(CONTENT, null)),
                HASH,
                true);
    }
}
