package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.runtime.ExecutionStoppedException;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WebContextExecutionControlAdapterTest {

    private static final String SESSION_ID = "session";
    private static final String EXECUTION_ID = "execution";

    @Test
    void acceptsRunningExecutionBeforeDeadline() {
        AgentExecutionService executions = mock(AgentExecutionService.class);
        when(executions.lockById(EXECUTION_ID)).thenReturn(running(Instant.now().plusSeconds(60)));
        WebContextExecutionControlAdapter adapter =
                new WebContextExecutionControlAdapter(executions);

        assertThatCode(() -> adapter.assertRunningAndWithinDeadline(reference()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsExecutionPastDeadline() {
        AgentExecutionService executions = mock(AgentExecutionService.class);
        when(executions.lockById(EXECUTION_ID)).thenReturn(running(Instant.now().minusSeconds(1)));
        WebContextExecutionControlAdapter adapter =
                new WebContextExecutionControlAdapter(executions);

        assertThatThrownBy(() -> adapter.assertRunningAndWithinDeadline(reference()))
                .isInstanceOf(ExecutionStoppedException.class);
    }

    private static AgentExecution running(Instant deadline) {
        AgentExecution execution = new AgentExecution();
        execution.setExecutionId(EXECUTION_ID);
        execution.setConversationSessionId(SESSION_ID);
        execution.setStatus("running");
        execution.setHardDeadlineAt(deadline);
        return execution;
    }

    private static ContextExecutionRef reference() {
        return new ContextExecutionRef(TenantContext.TENANT_ID, SESSION_ID, EXECUTION_ID, 1L);
    }
}
