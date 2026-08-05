package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextContractsTest {

    private static final String STRATEGY_RAW = "raw";

    @Test
    void budgetUsesWholeWindowForSoftThresholdAndReservesOutputForHardLimit() {
        ContextBudget budget = new ContextBudget(65_536, 4_096, 4_096, 8_000);

        assertThat(budget.softTriggerTokens()).isEqualTo(49_152);
        assertThat(budget.targetInputTokens()).isEqualTo(42_598);
        assertThat(budget.hardInputLimitTokens()).isEqualTo(57_344);
    }

    @Test
    void rejectsInvalidBudgetConfiguration() {
        assertThatThrownBy(() -> new ContextBudget(0, 4_096, 4_096, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextBudget(8_192, 4_096, 4_096, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preparedContextDefensivelyCopiesMessages() {
        List<ContextMessage> messages = new ArrayList<>();
        PreparedConversationContext prepared =
                new PreparedConversationContext(STRATEGY_RAW, messages, 0, false, 0L);

        messages.add(ContextMessage.user("late mutation"));

        assertThat(prepared.messages()).isEmpty();
    }

    @Test
    void policyRejectsUnsupportedFanoutAndLevel() {
        assertThatThrownBy(() -> policy(2, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy(5, 9)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ContextPolicySnapshot policy(int fanout, int maxLevel) {
        return new ContextPolicySnapshot(1, fanout, maxLevel, 10, 75, 65, 100, 109, 10);
    }
}
