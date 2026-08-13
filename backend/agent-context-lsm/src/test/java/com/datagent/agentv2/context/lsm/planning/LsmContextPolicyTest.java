package com.datagent.agentv2.context.lsm.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.context.lsm.domain.LsmContextPolicySnapshot;
import com.datagent.agentv2.conversation.context.ContextBudget;
import org.junit.jupiter.api.Test;

class LsmContextPolicyTest {

    private static final int CONTEXT_WINDOW_TOKENS = 65_536;
    private static final int MAX_OUTPUT_TOKENS = 4_096;
    private static final int SAFETY_RESERVE_TOKENS = 4_096;
    private static final int FIXED_INPUT_TOKENS = 0;
    private static final int SOFT_TRIGGER_TOKENS = 49_152;
    private static final int TARGET_INPUT_TOKENS = 42_598;
    private static final int HARD_INPUT_LIMIT_TOKENS = 57_344;

    private final LsmContextPolicy policy = new LsmContextPolicy();

    @Test
    void lsmPolicyKeepsExistingWatermarks() {
        LsmContextPolicySnapshot policy = LsmContextPolicySnapshot.defaults(5, 4);
        ContextBudget budget = new ContextBudget(65_536, 4_096, 4_096, 0);

        assertThat(policy.softTriggerTokens(budget)).isEqualTo(49_152);
        assertThat(policy.targetInputTokens(budget)).isEqualTo(42_598);
    }

    @Test
    void triggersOnlyAboveSeventyFivePercent() {
        assertThat(policy.requiresCompaction(SOFT_TRIGGER_TOKENS, budget(), snapshot())).isFalse();
        assertThat(policy.requiresCompaction(SOFT_TRIGGER_TOKENS + 1, budget(), snapshot())).isTrue();
    }

    @Test
    void exposesTargetWatermarkAndHardBoundaryWithoutSpendingSafetyReserve() {
        assertThat(policy.targetInputTokens(budget(), snapshot())).isEqualTo(TARGET_INPUT_TOKENS);
        assertThat(policy.exceedsHardInputLimit(HARD_INPUT_LIMIT_TOKENS, budget())).isFalse();
        assertThat(policy.exceedsHardInputLimit(HARD_INPUT_LIMIT_TOKENS + 1, budget())).isTrue();
    }

    private static ContextBudget budget() {
        return new ContextBudget(
                CONTEXT_WINDOW_TOKENS,
                MAX_OUTPUT_TOKENS,
                SAFETY_RESERVE_TOKENS,
                FIXED_INPUT_TOKENS);
    }

    private static LsmContextPolicySnapshot snapshot() {
        return LsmContextPolicySnapshot.defaults(5, 4);
    }
}
