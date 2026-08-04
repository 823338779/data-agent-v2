package com.datagent.agentv2.conversation.react;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.ThinkingProcessStatus;
import com.datagent.agentv2.conversation.domain.ToolThinkingProcessItem;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import com.datagent.agentv2.conversation.enums.ModelInvocationStatus;
import com.datagent.agentv2.conversation.enums.ToolExecutionStatus;
import com.datagent.agentv2.conversation.exception.ExecutionOwnershipLostException;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.persistence.entity.ModelInvocationEntity;
import com.datagent.agentv2.persistence.entity.ToolExecutionEntity;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ModelInvocationService;
import com.datagent.agentv2.persistence.service.ToolExecutionService;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReactRecoveryPlanner {

    private static final String ERROR_TERMINAL_PROCESS_ITEM =
            "Terminal tool execution is missing its process item result";

    private final AgentExecutionService agentExecutions;
    private final ModelInvocationService modelInvocations;
    private final ToolExecutionService toolExecutions;
    private final ConversationMessageService messages;

    public ReactRecoveryPlanner(
            AgentExecutionService agentExecutions,
            ModelInvocationService modelInvocations,
            ToolExecutionService toolExecutions,
            ConversationMessageService messages) {
        this.agentExecutions = agentExecutions;
        this.modelInvocations = modelInvocations;
        this.toolExecutions = toolExecutions;
        this.messages = messages;
    }

    @Transactional
    public RecoveryPlan recover(AgentExecutionPort.ClaimedAgentExecution run) {
        return recoverLocked(run);
    }

    private RecoveryPlan recoverLocked(AgentExecutionPort.ClaimedAgentExecution run) {
        assertOwned(run);
        List<ModelInvocationEntity> persistedModelInvocations =
                modelInvocations.findByExecutionId(run.executionId());
        if (persistedModelInvocations.isEmpty()) {
            return RecoveryPlan.continueModel();
        }
        ModelInvocationEntity last = persistedModelInvocations.getLast();
        if (ModelInvocationStatus.STREAMING.matches(last.getStatus())) {
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
            validateTerminalProcessItem(execution);
        }
        return RecoveryPlan.continueModel();
    }

    private void assertOwned(AgentExecutionPort.ClaimedAgentExecution run) {
        AgentExecution owned = agentExecutions.lockById(run.executionId());
        if (owned == null
                || !ExecutionStatus.RUNNING.value().equals(owned.getStatus())
                || !run.ownerInstanceId().equals(owned.getOwnerInstanceId())
                || run.executionAttemptNumber() != owned.getExecutionAttemptNumber()) {
            throw new ExecutionOwnershipLostException(run.executionId());
        }
    }

    private boolean needsExecution(ToolExecutionEntity execution) {
        return ToolExecutionStatus.PREPARED.matches(execution.getStatus())
                || ToolExecutionStatus.EXECUTING.matches(execution.getStatus());
    }

    private void validateTerminalProcessItem(ToolExecutionEntity execution) {
        if (!ToolExecutionStatus.SUCCEEDED.matches(execution.getStatus())
                && !ToolExecutionStatus.FAILED.matches(execution.getStatus())) {
            throw new IllegalStateException(ERROR_TERMINAL_PROCESS_ITEM);
        }
        ConversationMessage assistant =
                messages.findByConversationMessageId(execution.getAssistantConversationMessageId());
        if (assistant == null || execution.getToolProcessItemId() == null) {
            throw new IllegalStateException(ERROR_TERMINAL_PROCESS_ITEM);
        }
        ToolThinkingProcessItem item;
        try {
            if (!(assistant.requireProcessItem(execution.getToolProcessItemId())
                    instanceof ToolThinkingProcessItem toolItem)) {
                throw new IllegalStateException(ERROR_TERMINAL_PROCESS_ITEM);
            }
            item = toolItem;
        } catch (IllegalStateException failure) {
            throw new IllegalStateException(ERROR_TERMINAL_PROCESS_ITEM, failure);
        }
        // 工具行和中心消息中的终态节点必须描述同一次调用及同一成功/失败结果。
        boolean succeeded = ToolExecutionStatus.SUCCEEDED.matches(execution.getStatus());
        ThinkingProcessStatus expectedStatus =
                succeeded ? ThinkingProcessStatus.SUCCEEDED : ThinkingProcessStatus.FAILED;
        if (item.status() != expectedStatus
                || (succeeded && item.output() == null)
                || !Boolean.valueOf(succeeded).equals(item.success())
                || !execution.getToolExecutionId().equals(item.toolExecutionId())
                || !execution.getToolCallId().equals(item.toolCallId())
                || !execution.getToolName().equals(item.toolName())) {
            throw new IllegalStateException(ERROR_TERMINAL_PROCESS_ITEM);
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
