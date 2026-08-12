package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.conversation.context.ContextExecutionControlPort.ContextLifecycleEvent;
import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import com.datagent.agentv2.conversation.exception.ExecutionDeadlineExceededException;
import com.datagent.agentv2.conversation.exception.ExecutionOwnershipLostException;
import com.datagent.agentv2.conversation.process.AgentExecutionProcessTerminationService;
import com.datagent.agentv2.persistence.service.AgentExecutionEventService;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.platform.context.TenantContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebContextExecutionControlAdapterTest {

    private static final String TENANT_ID = "tenant";
    private static final String SESSION_ID = "session";
    private static final String EXECUTION_ID = "execution";
    private static final String OWNER_ID = "owner";

    @Test
    void expiredLeaseFencesPublicationBeforeLifecycleEvent() {
        AgentExecutionService executions = mock(AgentExecutionService.class);
        AgentExecutionEventService events = mock(AgentExecutionEventService.class);
        WebContextExecutionControlAdapter adapter = adapter(executions, events);
        AgentExecution locked = runningExecution();
        locked.setLeaseExpiresAt(Instant.now().minusSeconds(1));
        locked.setHardDeadlineAt(Instant.now().plusSeconds(60));
        when(executions.lockById(EXECUTION_ID)).thenReturn(locked);

        assertThatThrownBy(
                        () ->
                                TenantContext.run(
                                        TENANT_ID,
                                        () ->
                                                adapter.publish(
                                                        executionRef(),
                                                        new ContextLifecycleEvent(
                                                                ContextLifecycleEvent.Type
                                                                        .COMPLETED,
                                                                Map.of()))))
                .isInstanceOf(ExecutionOwnershipLostException.class);
        verifyNoInteractions(events);
    }

    @Test
    void hardDeadlineRemainsASeparateFenceForAnActiveLease() {
        AgentExecutionService executions = mock(AgentExecutionService.class);
        WebContextExecutionControlAdapter adapter =
                adapter(executions, mock(AgentExecutionEventService.class));
        AgentExecution locked = runningExecution();
        locked.setLeaseExpiresAt(Instant.now().plusSeconds(60));
        locked.setHardDeadlineAt(Instant.now().minusSeconds(1));
        when(executions.lockById(EXECUTION_ID)).thenReturn(locked);

        assertThatThrownBy(
                        () ->
                                TenantContext.run(
                                        TENANT_ID,
                                        () -> adapter.assertOwnedAndWithinBudget(executionRef())))
                .isInstanceOf(ExecutionDeadlineExceededException.class);
    }

    private static WebContextExecutionControlAdapter adapter(
            AgentExecutionService executions, AgentExecutionEventService events) {
        return new WebContextExecutionControlAdapter(
                executions,
                mock(AgentExecutionProcessTerminationService.class),
                mock(ConversationSessionService.class),
                events,
                mock(IdGenerator.class));
    }

    private static AgentExecution runningExecution() {
        AgentExecution execution = new AgentExecution();
        execution.setExecutionId(EXECUTION_ID);
        execution.setConversationSessionId(SESSION_ID);
        execution.setStatus(ExecutionStatus.RUNNING.value());
        execution.setOwnerInstanceId(OWNER_ID);
        execution.setExecutionAttemptNumber(1L);
        return execution;
    }

    private static ContextExecutionRef executionRef() {
        return new ContextExecutionRef(TENANT_ID, SESSION_ID, EXECUTION_ID, OWNER_ID, 1L, 1L);
    }
}
