package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ContextSummaryRequestTest {

    @Test
    void preservesSeparateRenderedOutputAndInputBudgets() {
        ContextSummaryRequest request =
                new ContextSummaryRequest("system", "source", "{}", 256, 1024, 4096);

        assertThat(request.targetRenderedTokens()).isEqualTo(256);
        assertThat(request.maxOutputTokens()).isEqualTo(1024);
        assertThat(request.inputHardTokenBudget()).isEqualTo(4096);
    }

    @Test
    void rejectsNonPositiveBudgets() {
        assertThatThrownBy(() -> new ContextSummaryRequest("system", "source", "{}", 0, 1024, 4096))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextSummaryRequest("system", "source", "{}", 256, 0, 4096))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextSummaryRequest("system", "source", "{}", 256, 1024, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
