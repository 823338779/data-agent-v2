package com.datagent.agentv2.conversation.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.conversation.runtime.RunExecutionPort;
import com.datagent.agentv2.persistence.entity.AgentModelTurnEntity;
import com.datagent.agentv2.persistence.entity.AgentRunEntity;
import com.datagent.agentv2.persistence.entity.AgentToolExecutionEntity;
import com.datagent.agentv2.persistence.service.AgentMessageService;
import com.datagent.agentv2.persistence.service.AgentModelTurnService;
import com.datagent.agentv2.persistence.service.AgentRunService;
import com.datagent.agentv2.persistence.service.AgentToolExecutionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class ReactCrashRecoveryIntegrationTest {

    private static final RunExecutionPort.OwnedRun OWNED =
            new RunExecutionPort.OwnedRun("tenant", "run", "new-owner", 2);

    private final AgentRunService runs = mock(AgentRunService.class);
    private final AgentModelTurnService turns = mock(AgentModelTurnService.class);
    private final AgentToolExecutionService executions = mock(AgentToolExecutionService.class);
    private final AgentMessageService messages = mock(AgentMessageService.class);
    private final ReactRecoveryPlanner planner =
            new ReactRecoveryPlanner(runs, turns, executions, messages, transactionManager());

    @Test
    void abandonsStreamingTurnAndRetriesFromItsImmutableInputBoundary() {
        ownedRun();
        AgentModelTurnEntity streaming = turn("streaming", 1, 4);
        when(turns.findByRunId("run")).thenReturn(List.of(streaming));
        when(turns.abandonRecovered(
                        eq("run"), eq(1L), eq(1L), eq(0L), eq("new-owner"), eq(2L), any()))
                .thenReturn(true);

        ReactRecoveryPlanner.RecoveryPlan plan = planner.recover(OWNED);

        assertThat(plan)
                .isEqualTo(
                        new ReactRecoveryPlanner.RecoveryPlan(
                                ReactRecoveryPlanner.Action.RETRY_MODEL, 1, 4));
        verify(turns)
                .abandonRecovered(
                        eq("run"), eq(1L), eq(1L), eq(0L), eq("new-owner"), eq(2L), any());
    }

    @Test
    void resumesPreparedAndLegacyExecutingToolsWithoutStartingAnotherModelFirst() {
        ownedRun();
        AgentModelTurnEntity completed = turn("completed", 1, 4);
        when(turns.findByRunId("run")).thenReturn(List.of(completed));
        when(executions.findByTurn("run", 1))
                .thenReturn(List.of(execution("prepared", null), execution("executing", null)));

        assertThat(planner.recover(OWNED).action())
                .isEqualTo(ReactRecoveryPlanner.Action.EXECUTE_TOOLS);
    }

    @Test
    void skipsTerminalExecutionsOnlyWhenTheirUniqueObservationsExist() {
        ownedRun();
        when(turns.findByRunId("run")).thenReturn(List.of(turn("completed", 1, 4)));
        when(executions.findByTurn("run", 1))
                .thenReturn(
                        List.of(
                                execution("succeeded", "observation-1"),
                                execution("failed", "observation-2")));
        when(messages.findByMessageId("observation-1"))
                .thenReturn(observation("execution-succeeded"));
        when(messages.findByMessageId("observation-2")).thenReturn(observation("execution-failed"));

        assertThat(planner.recover(OWNED).action())
                .isEqualTo(ReactRecoveryPlanner.Action.CONTINUE_MODEL);

        when(messages.findByMessageId("observation-2")).thenReturn(null);
        assertThatThrownBy(() -> planner.recover(OWNED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Terminal tool execution is missing its observation");
    }

    private void ownedRun() {
        AgentRunEntity run = new AgentRunEntity();
        run.setRunId("run");
        run.setStatus("running");
        run.setOwnerInstanceId("new-owner");
        run.setExecutionAttempt(2L);
        when(runs.lockById("run")).thenReturn(run);
    }

    private static AgentModelTurnEntity turn(String status, long sequence, long boundary) {
        AgentModelTurnEntity turn = new AgentModelTurnEntity();
        turn.setRunId("run");
        turn.setStatus(status);
        turn.setModelTurnSequence(sequence);
        turn.setExecutionAttempt(1L);
        turn.setInputUpToMessageSequence(boundary);
        turn.setVersion(0L);
        return turn;
    }

    private static AgentToolExecutionEntity execution(String status, String observationId) {
        AgentToolExecutionEntity execution = new AgentToolExecutionEntity();
        execution.setToolExecutionId("execution-" + status);
        execution.setStatus(status);
        execution.setObservationMessageId(observationId);
        return execution;
    }

    private static com.datagent.agentv2.persistence.entity.AgentMessageEntity observation(
            String executionId) {
        com.datagent.agentv2.persistence.entity.AgentMessageEntity message =
                new com.datagent.agentv2.persistence.entity.AgentMessageEntity();
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
