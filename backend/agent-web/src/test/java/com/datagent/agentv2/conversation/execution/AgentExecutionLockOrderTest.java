package com.datagent.agentv2.conversation.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.process.AgentExecutionProcessTerminationService;
import com.datagent.agentv2.persistence.service.*;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class AgentExecutionLockOrderTest {
    @Test
    void finalAnswerLocksSessionBeforeWaitingForExecution() throws Exception {
        var sessions = mock(ConversationSessionService.class);
        var executions = mock(AgentExecutionService.class);
        var sessionLocked = new AtomicBoolean();
        when(sessions.lockById("session"))
                .thenAnswer(
                        call -> {
                            sessionLocked.set(true);
                            return null;
                        });
        when(executions.lockById("run"))
                .thenAnswer(
                        call -> {
                            assertThat(sessionLocked.get()).isTrue();
                            throw new IllegalStateException("lock-order-observed");
                        });
        var constructor =
                com.datagent.agentv2.conversation.react.ModelInvocationCoordinator.class
                        .getDeclaredConstructors()[0];
        Object[] dependencies =
                java.util.Arrays.stream(constructor.getParameterTypes())
                        .map(
                                type -> {
                                    if (type == ConversationSessionService.class) {
                                        return sessions;
                                    }
                                    if (type == AgentExecutionService.class) {
                                        return executions;
                                    }
                                    return mock(type);
                                })
                        .toArray();
        var coordinator =
                (com.datagent.agentv2.conversation.react.ModelInvocationCoordinator)
                        constructor.newInstance(dependencies);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                coordinator.completeFinalAnswer(
                                        new com.datagent.agentv2.conversation.runtime
                                                .LocalAgentExecution("tenant", "session", "run", 2),
                                        new com.datagent.agentv2.conversation.react
                                                .ModelInvocationCoordinator.ModelInvocationBoundary(
                                                1, 2),
                                        "answer"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("lock-order-observed");
    }

    @Test
    void cancellationLocksSessionBeforeWaitingForExecution() {
        var sessions = mock(ConversationSessionService.class);
        var executions = mock(AgentExecutionService.class);
        var sessionLocked = new AtomicBoolean();
        when(sessions.lockById("session"))
                .thenAnswer(
                        call -> {
                            sessionLocked.set(true);
                            return null;
                        });
        when(executions.lockById("run"))
                .thenAnswer(
                        call -> {
                            assertThat(sessionLocked.get())
                                    .as("会话提交也先持有 session 锁，必须统一锁序")
                                    .isTrue();
                            return AgentExecution.running(
                                    "run",
                                    "session",
                                    "message",
                                    "key",
                                    "hash",
                                    Instant.now().plusSeconds(60));
                        });
        var ids = mock(IdGenerator.class);
        when(ids.next(anyString())).thenReturn("event");
        new AgentExecutionControlService(
                        sessions,
                        executions,
                        mock(AgentExecutionEventService.class),
                        mock(AgentExecutionProcessTerminationService.class),
                        mock(ApplicationEventPublisher.class),
                        ids)
                .cancel("session", "run");
    }
}
