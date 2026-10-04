package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.hierarchical.persistence.entity.ContextPayloadEntity;
import com.datagent.agentv2.context.hierarchical.persistence.repository.ContextPayloadRepository;
import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.ConversationSession;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HierarchicalContextResultReaderTest {
    private static final String ID = "source";
    private static final String TEXT = "中😀文";
    private static final String HASH = "0".repeat(64);
    private final ContextPayloadRepository payloads = mock(ContextPayloadRepository.class);
    private final AgentExecutionService executions = mock(AgentExecutionService.class);
    private final ConversationSessionService sessions = mock(ConversationSessionService.class);
    private final ContextModelBudgetProvider budgets = mock(ContextModelBudgetProvider.class);
    private final ContextResultReaderAdapter reader =
            new ContextResultReaderAdapter(payloads, executions, sessions, budgets, 4096);

    @Test
    void pageHonorsUtf8Boundaries() {
        fixture();
        var first = reader.read(ID, ID, 0, 6);
        assertThat(first.content()).isEqualTo(TEXT.substring(0, 1));
        assertThat(first.nextOffsetBytes()).isEqualTo(3);
        var second = reader.read(ID, ID, 3, 4);
        assertThat(second.content()).isEqualTo(TEXT.substring(1, 3));
        assertThat(second.nextOffsetBytes()).isEqualTo(7);
        assertThatThrownBy(() -> reader.read(ID, ID, 3, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readRejectsForeignSessionAndInvisibleSource() {
        fixture();
        when(payloads.findVisible(ID, ID)).thenReturn(null);
        assertThatThrownBy(() -> reader.read(ID, ID, 0, 4096))
                .isInstanceOf(IllegalArgumentException.class);
        when(sessions.findByUuid(ID)).thenReturn(null);
        assertThatThrownBy(() -> reader.read(ID, ID, 0, 4096))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void fixture() {
        when(executions.findByExecutionId(ID))
                .thenReturn(AgentExecution.running(ID, ID, ID, ID, HASH, Instant.MAX));
        when(sessions.findByUuid(ID)).thenReturn(mock(ConversationSession.class));
        when(budgets.currentBudget()).thenReturn(new ContextBudget(65536, 1024, 1024, 0));
        var payload = ContextPayloadEntity.create(ID, ID, ID, ID, ID, ID, HASH, TEXT);
        when(payloads.findVisible(ID, ID)).thenReturn(payload);
    }
}
