package com.datagent.agentv2.conversation.react;

import com.datagent.agentv2.conversation.runtime.RunExecutionPort;
import com.datagent.agentv2.conversation.runtime.RunOwnershipLostException;
import com.datagent.agentv2.persistence.entity.AgentMessageEntity;
import com.datagent.agentv2.persistence.entity.AgentModelTurnEntity;
import com.datagent.agentv2.persistence.entity.AgentRunEntity;
import com.datagent.agentv2.persistence.entity.AgentToolExecutionEntity;
import com.datagent.agentv2.persistence.service.AgentMessageService;
import com.datagent.agentv2.persistence.service.AgentModelTurnService;
import com.datagent.agentv2.persistence.service.AgentRunService;
import com.datagent.agentv2.persistence.service.AgentToolExecutionService;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class ReactRecoveryPlanner {

    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_STREAMING = "streaming";
    private static final String STATUS_PREPARED = "prepared";
    private static final String STATUS_EXECUTING = "executing";
    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String STATUS_FAILED = "failed";
    private static final String ROLE_TOOL = "tool";
    private static final String ERROR_TERMINAL_OBSERVATION =
            "Terminal tool execution is missing its observation";

    private final AgentRunService runs;
    private final AgentModelTurnService turns;
    private final AgentToolExecutionService executions;
    private final AgentMessageService messages;
    private final TransactionTemplate transactions;

    public ReactRecoveryPlanner(
            AgentRunService runs,
            AgentModelTurnService turns,
            AgentToolExecutionService executions,
            AgentMessageService messages,
            PlatformTransactionManager transactionManager) {
        this.runs = runs;
        this.turns = turns;
        this.executions = executions;
        this.messages = messages;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public RecoveryPlan recover(RunExecutionPort.OwnedRun run) {
        return Objects.requireNonNull(transactions.execute(ignored -> recoverLocked(run)));
    }

    private RecoveryPlan recoverLocked(RunExecutionPort.OwnedRun run) {
        assertOwned(run);
        List<AgentModelTurnEntity> persistedTurns = turns.findByRunId(run.runId());
        if (persistedTurns.isEmpty()) {
            return RecoveryPlan.continueModel();
        }
        AgentModelTurnEntity last = persistedTurns.getLast();
        if (STATUS_STREAMING.equals(last.getStatus())) {
            if (!turns.abandonRecovered(
                    run.runId(),
                    last.getModelTurnSequence(),
                    last.getExecutionAttempt(),
                    last.getVersion(),
                    run.ownerInstanceId(),
                    run.executionAttempt(),
                    Instant.now())) {
                throw new RunOwnershipLostException(run.runId());
            }
            return new RecoveryPlan(
                    Action.RETRY_MODEL,
                    last.getModelTurnSequence(),
                    last.getInputUpToMessageSequence());
        }

        List<AgentToolExecutionEntity> toolExecutions =
                executions.findByTurn(run.runId(), last.getModelTurnSequence());
        if (toolExecutions.isEmpty()) {
            return RecoveryPlan.continueModel();
        }
        if (toolExecutions.stream().anyMatch(this::needsExecution)) {
            return new RecoveryPlan(
                    Action.EXECUTE_TOOLS,
                    last.getModelTurnSequence(),
                    last.getInputUpToMessageSequence());
        }
        for (AgentToolExecutionEntity execution : toolExecutions) {
            validateTerminalObservation(execution);
        }
        return RecoveryPlan.continueModel();
    }

    private void assertOwned(RunExecutionPort.OwnedRun run) {
        AgentRunEntity owned = runs.lockById(run.runId());
        if (owned == null
                || !STATUS_RUNNING.equals(owned.getStatus())
                || !run.ownerInstanceId().equals(owned.getOwnerInstanceId())
                || run.executionAttempt() != owned.getExecutionAttempt()) {
            throw new RunOwnershipLostException(run.runId());
        }
    }

    private boolean needsExecution(AgentToolExecutionEntity execution) {
        return STATUS_PREPARED.equals(execution.getStatus())
                || STATUS_EXECUTING.equals(execution.getStatus());
    }

    private void validateTerminalObservation(AgentToolExecutionEntity execution) {
        if (!STATUS_SUCCEEDED.equals(execution.getStatus())
                && !STATUS_FAILED.equals(execution.getStatus())) {
            throw new IllegalStateException(ERROR_TERMINAL_OBSERVATION);
        }
        AgentMessageEntity observation =
                messages.findByMessageId(execution.getObservationMessageId());
        if (observation == null
                || !ROLE_TOOL.equals(observation.getRole())
                || !execution.getToolExecutionId().equals(observation.getToolExecutionId())) {
            throw new IllegalStateException(ERROR_TERMINAL_OBSERVATION);
        }
    }

    public enum Action {
        CONTINUE_MODEL,
        RETRY_MODEL,
        EXECUTE_TOOLS
    }

    public record RecoveryPlan(Action action, long modelTurnSequence, long inputBoundary) {
        public static RecoveryPlan continueModel() {
            return new RecoveryPlan(Action.CONTINUE_MODEL, 0, 0);
        }
    }
}
