package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextContractsTest {

    private static final String STRATEGY_RAW = "raw";

    @Test
    void contextBudgetOnlyOwnsPhysicalLimits() {
        ContextBudget budget = new ContextBudget(65_536, 4_096, 4_096, 8_000);

        assertThat(budget.hardInputLimitTokens()).isEqualTo(57_344);
    }

    @Test
    void requestContainsNoStrategyPolicy() {
        ConversationContextRequest request =
                new ConversationContextRequest(execution(), budget(), List.of(), List.of());

        assertThat(request.execution().executionId()).isEqualTo("execution-1");
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
    void preparedContextUsesStrategyNeutralGeneration() {
        PreparedConversationContext prepared =
                new PreparedConversationContext(STRATEGY_RAW, List.of(), 12, false, 0L);

        assertThat(prepared.strategyGeneration()).isZero();
    }

    private static ContextExecutionRef execution() {
        return new ContextExecutionRef("tenant-1", "session-1", "execution-1", "owner-1", 1L, 4L);
    }

    private static ContextBudget budget() {
        return new ContextBudget(65_536, 4_096, 4_096, 0);
    }
}
