package com.datagent.agentv2.context.hierarchical.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.conversation.context.ContextBudget;
import org.junit.jupiter.api.Test;

class HierarchicalContextPolicyTest {

    private static final int SOFT_TRIGGER_TOKENS = 49_152;
    private static final int TARGET_INPUT_TOKENS = 45_875;
    private static final int HARD_INPUT_LIMIT_TOKENS = 57_344;

    private final HierarchicalContextPolicy policy = new HierarchicalContextPolicy(snapshot());

    @Test
    void computesConfiguredWatermarksFromPhysicalBudget() {
        ContextBudget budget = new ContextBudget(65_536, 4_096, 4_096, 0);

        assertThat(policy.softTriggerTokens(budget)).isEqualTo(SOFT_TRIGGER_TOKENS);
        assertThat(policy.targetInputTokens(budget)).isEqualTo(TARGET_INPUT_TOKENS);
        assertThat(policy.hardInputLimitTokens(budget)).isEqualTo(HARD_INPUT_LIMIT_TOKENS);
    }

    @Test
    void protectsHotUntilTheHardLimitIsActuallyExceeded() {
        ContextBudget budget = new ContextBudget(65_536, 4_096, 4_096, 0);

        assertThat(policy.requiresCompaction(SOFT_TRIGGER_TOKENS, budget)).isFalse();
        assertThat(policy.requiresCompaction(SOFT_TRIGGER_TOKENS + 1, budget)).isTrue();
        assertThat(policy.requiresHotDegradation(HARD_INPUT_LIMIT_TOKENS, budget)).isFalse();
        assertThat(policy.requiresHotDegradation(HARD_INPUT_LIMIT_TOKENS + 1, budget)).isTrue();
    }

    private static HierarchicalContextPolicySnapshot snapshot() {
        return new HierarchicalContextPolicySnapshot(1, 0.75d, 0.70d, 10, 100, 1, 1);
    }
}
