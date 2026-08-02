package com.datagent.agentv2.conversation.react;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.conversation.runtime.ExecutionOwnershipLostException;
import com.datagent.agentv2.persistence.entity.ModelInvocationEntity;
import com.datagent.agentv2.persistence.entity.ToolExecutionEntity;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ModelInvocationService;
import com.datagent.agentv2.persistence.service.ToolExecutionService;
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

    private final AgentExecutionService agentExecutions;
    private final ModelInvocationService modelInvocations;
    private final ToolExecutionService toolExecutions;
    private final ConversationMessageService messages;
    private final TransactionTemplate transactions;

    public ReactRecoveryPlanner(
            AgentExecutionService agentExecutions,
            ModelInvocationService modelInvocations,
            ToolExecutionService toolExecutions,
            ConversationMessageService messages,
            PlatformTransactionManager transactionManager) {
        this.agentExecutions = agentExecutions;
        this.modelInvocations = modelInvocations;
        this.toolExecutions = toolExecutions;
        this.messages = messages;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public RecoveryPlan recover(AgentExecutionPort.ClaimedAgentExecution run) {
        return Objects.requireNonNull(transactions.execute(ignored -> recoverLocked(run)));
    }

    private RecoveryPlan recoverLocked(AgentExecutionPort.ClaimedAgentExecution run) {
        assertOwned(run);
        List<ModelInvocationEntity> persistedModelInvocations =
                modelInvocations.findByExecutionId(run.executionId());
        if (persistedModelInvocations.isEmpty()) {
            return RecoveryPlan.continueModel();
        }
        ModelInvocationEntity last = persistedModelInvocations.getLast();
        if (STATUS_STREAMING.equals(last.getStatus())) {
            if (!modelInvocations.abandonRecovered(
                    run.executionId(),
                    last.getModelInvocationSequence(),
                    last.getExecutionAttemptNumber(),
                    last.getVersion(),
                    run.ownerInstanceId(),
                    run.executionAttemptNumber(),
                    Instant.now())) {
                throw new ExecutionOwnershipLostException(run.executionId());
            }
            return new RecoveryPlan(
                    Action.RETRY_MODEL,
                    last.getModelInvocationSequence(),
                    last.getInputUpToConversationMessageSequence());
        }

        List<ToolExecutionEntity> toolExecutionRows =
                toolExecutions.findByTurn(run.executionId(), last.getModelInvocationSequence());
        if (toolExecutionRows.isEmpty()) {
            return RecoveryPlan.continueModel();
        }
        if (toolExecutionRows.stream().anyMatch(this::needsExecution)) {
            return new RecoveryPlan(
                    Action.EXECUTE_TOOLS,
                    last.getModelInvocationSequence(),
                    last.getInputUpToConversationMessageSequence());
        }
        for (ToolExecutionEntity execution : toolExecutionRows) {
            validateTerminalObservation(execution);
        }
        return RecoveryPlan.continueModel();
    }

    private void assertOwned(AgentExecutionPort.ClaimedAgentExecution run) {
        AgentExecution owned = agentExecutions.lockById(run.executionId());
        if (owned == null
                || !STATUS_RUNNING.equals(owned.getStatus())
                || !run.ownerInstanceId().equals(owned.getOwnerInstanceId())
                || run.executionAttemptNumber() != owned.getExecutionAttemptNumber()) {
            throw new ExecutionOwnershipLostException(run.executionId());
        }
    }

    private boolean needsExecution(ToolExecutionEntity execution) {
        return STATUS_PREPARED.equals(execution.getStatus())
                || STATUS_EXECUTING.equals(execution.getStatus());
    }

    private void validateTerminalObservation(ToolExecutionEntity execution) {
        if (!STATUS_SUCCEEDED.equals(execution.getStatus())
                && !STATUS_FAILED.equals(execution.getStatus())) {
            throw new IllegalStateException(ERROR_TERMINAL_OBSERVATION);
        }
        ConversationMessage observation =
                messages.findByConversationMessageId(
                        execution.getObservationConversationMessageId());
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

    public record RecoveryPlan(Action action, long modelInvocationSequence, long inputBoundary) {
        public static RecoveryPlan continueModel() {
            return new RecoveryPlan(Action.CONTINUE_MODEL, 0, 0);
        }
    }
}
