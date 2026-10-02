package com.datagent.agentv2.conversation.execution;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AgentExecutionSubmissionValidationTest {
    @Test
    void rejectsOverlongKeyBeforeOpeningTheCreationTransaction() {
        var transactions = mock(AgentExecutionSubmissionTransactionService.class);
        var service = new AgentExecutionSubmissionService(transactions);
        assertThatThrownBy(() -> service.submit("session", "message", "k".repeat(257)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(
                        failure ->
                                org.assertj.core.api.Assertions.assertThat(
                                                ((ResponseStatusException) failure)
                                                        .getStatusCode()
                                                        .value())
                                        .isEqualTo(400));
        verifyNoInteractions(transactions);
    }

    @Test
    void acceptsKeyAtTheDatabaseLimit() {
        var transactions = mock(AgentExecutionSubmissionTransactionService.class);
        new AgentExecutionSubmissionService(transactions)
                .submit("session", "message", "k".repeat(256));
        verify(transactions)
                .createOrReplay(eq("session"), eq("message"), eq("k".repeat(256)), anyString());
    }
}
