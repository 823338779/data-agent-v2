package com.datagent.agentv2.conversation.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.persistence.entity.AgentExecutionEntity;
import com.datagent.agentv2.persistence.entity.ModelInvocationEntity;
import com.datagent.agentv2.persistence.entity.ToolExecutionEntity;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ModelInvocationService;
import com.datagent.agentv2.persistence.service.ToolExecutionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class ReactCrashRecoveryIntegrationTest {

    private static final AgentExecutionPort.ClaimedAgentExecution OWNED =
            new AgentExecutionPort.ClaimedAgentExecution("tenant", "run", "new-owner", 2);

    private final AgentExecutionService agentExecutions = mock(AgentExecutionService.class);
    private final ModelInvocationService modelInvocations = mock(ModelInvocationService.class);
    private final ToolExecutionService toolExecutions = mock(ToolExecutionService.class);
    private final ConversationMessageService messages = mock(ConversationMessageService.class);
    private final ReactRecoveryPlanner planner =
            new ReactRecoveryPlanner(
                    agentExecutions,
                    modelInvocations,
                    toolExecutions,
                    messages,
                    transactionManager());

    @Test
    void abandonsStreamingTurnAndRetriesFromItsImmutableInputBoundary() {
        stubOwnedExecution();
        ModelInvocationEntity streaming = modelInvocation("streaming", 1, 4);
        when(modelInvocations.findByExecutionId("run")).thenReturn(List.of(streaming));
        when(modelInvocations.abandonRecovered(
                        eq("run"), eq(1L), eq(1L), eq(0L), eq("new-owner"), eq(2L), any()))
                .thenReturn(true);

        ReactRecoveryPlanner.RecoveryPlan plan = planner.recover(OWNED);

        assertThat(plan)
                .isEqualTo(
                        new ReactRecoveryPlanner.RecoveryPlan(
                                ReactRecoveryPlanner.Action.RETRY_MODEL, 1, 4));
        verify(modelInvocations)
                .abandonRecovered(
                        eq("run"), eq(1L), eq(1L), eq(0L), eq("new-owner"), eq(2L), any());
    }

    @Test
    void resumesPreparedAndLegacyExecutingToolsWithoutStartingAnotherModelFirst() {
        stubOwnedExecution();
        ModelInvocationEntity completed = modelInvocation("completed", 1, 4);
        when(modelInvocations.findByExecutionId("run")).thenReturn(List.of(completed));
        when(toolExecutions.findByTurn("run", 1))
                .thenReturn(List.of(execution("prepared", null), execution("executing", null)));

        assertThat(planner.recover(OWNED).action())
                .isEqualTo(ReactRecoveryPlanner.Action.EXECUTE_TOOLS);
    }

    @Test
    void skipsTerminalExecutionsOnlyWhenTheirUniqueObservationsExist() {
        stubOwnedExecution();
        when(modelInvocations.findByExecutionId("run"))
                .thenReturn(List.of(modelInvocation("completed", 1, 4)));
        when(toolExecutions.findByTurn("run", 1))
                .thenReturn(
                        List.of(
                                execution("succeeded", "observation-1"),
                                execution("failed", "observation-2")));
        when(messages.findByConversationMessageId("observation-1"))
                .thenReturn(observation("execution-succeeded"));
        when(messages.findByConversationMessageId("observation-2"))
                .thenReturn(observation("execution-failed"));

        assertThat(planner.recover(OWNED).action())
                .isEqualTo(ReactRecoveryPlanner.Action.CONTINUE_MODEL);

        when(messages.findByConversationMessageId("observation-2")).thenReturn(null);
        assertThatThrownBy(() -> planner.recover(OWNED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Terminal tool execution is missing its observation");
    }

    private void stubOwnedExecution() {
        AgentExecutionEntity run = new AgentExecutionEntity();
        run.setExecutionId("run");
        run.setStatus("running");
        run.setOwnerInstanceId("new-owner");
        run.setExecutionAttemptNumber(2L);
        when(agentExecutions.lockById("run")).thenReturn(run);
    }

    private static ModelInvocationEntity modelInvocation(
            String status, long sequence, long boundary) {
        ModelInvocationEntity modelInvocation = new ModelInvocationEntity();
        modelInvocation.setExecutionId("run");
        modelInvocation.setStatus(status);
        modelInvocation.setModelInvocationSequence(sequence);
        modelInvocation.setExecutionAttemptNumber(1L);
        modelInvocation.setInputUpToConversationMessageSequence(boundary);
        modelInvocation.setVersion(0L);
        return modelInvocation;
    }

    private static ToolExecutionEntity execution(String status, String observationId) {
        ToolExecutionEntity execution = new ToolExecutionEntity();
        execution.setToolExecutionId("execution-" + status);
        execution.setStatus(status);
        execution.setObservationConversationMessageId(observationId);
        return execution;
    }

    private static com.datagent.agentv2.persistence.entity.ConversationMessageEntity observation(
            String executionId) {
        com.datagent.agentv2.persistence.entity.ConversationMessageEntity message =
                new com.datagent.agentv2.persistence.entity.ConversationMessageEntity();
        message.setRole("tool");
        message.setToolExecutionId(executionId);
        return message;
    }

    private static PlatformTransactionManager transactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
        return manager;
    }
}
