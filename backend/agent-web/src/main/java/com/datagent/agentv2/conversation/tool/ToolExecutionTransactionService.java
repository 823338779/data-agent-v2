package com.datagent.agentv2.conversation.tool;

import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.ToolThinkingProcessItem;
import com.datagent.agentv2.conversation.enums.ToolExecutionStatus;
import com.datagent.agentv2.conversation.exception.ExecutionOwnershipLostException;
import com.datagent.agentv2.conversation.process.ConversationProcessCoordinator;
import com.datagent.agentv2.conversation.process.ProcessAuditEvent;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.persistence.entity.ToolExecutionEntity;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ToolExecutionService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 承载工具调用前后的独立短事务，确保外部工具执行期间不持有数据库事务。 */
@Service
@RequiredArgsConstructor
public class ToolExecutionTransactionService {

    private static final String IDEMPOTENCY_PREFIX = "tool";
    private static final String IDEMPOTENCY_SEPARATOR = ":";
    private static final String ERROR_INVALID_ASSISTANT_CALL =
            "Prepared tool execution has no matching assistant tool call";

    private final AgentToolRegistry registry;
    private final ToolExecutionService toolExecutions;
    private final ConversationMessageService messages;
    private final ConversationProcessCoordinator conversationProcesses;
    private final ObjectMapper json;

    /** 在独立事务中把 prepared 工具执行推进为 executing，并返回不可变调用输入。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PreparedInvocation beginInvocation(
            AgentExecutionPort.ClaimedAgentExecution run, ToolExecutionEntity candidate) {
        // 重新读取当前执行状态，避免使用循环开始时取得的过期版本。
        ToolExecutionEntity execution =
                toolExecutions.findByToolExecutionId(candidate.getToolExecutionId());
        if (execution == null || isTerminal(execution.getStatus())) {
            throw new IllegalStateException(ERROR_INVALID_ASSISTANT_CALL);
        }
        ConversationMessage assistant =
                messages.findByConversationMessageId(execution.getAssistantConversationMessageId());
        ToolThinkingProcessItem processItem = toolItemFor(assistant, execution);
        JsonNode arguments = argumentsFor(processItem);
        AgentTool tool = registry.find(execution.getToolName()).orElse(null);
        String idempotencyKey = idempotencyKey(run, execution, tool);

        // 在提交前通过 owner fencing 原子推进状态，并同步内存快照版本。
        Instant startedAt = Instant.now();
        conversationProcesses.startTool(run, execution.getToolProcessItemId(), startedAt);
        if (!toolExecutions.startExecution(
                execution.getToolExecutionId(),
                execution.getVersion(),
                startedAt,
                idempotencyKey,
                run.ownerInstanceId(),
                run.executionAttemptNumber())) {
            throw new ExecutionOwnershipLostException(run.executionId());
        }
        execution.setStatus(ToolExecutionStatus.EXECUTING.value());
        execution.setExecutionAttemptNumber(run.executionAttemptNumber());
        execution.setExecutionCount(execution.getExecutionCount() + 1);
        execution.setIdempotencyKey(idempotencyKey);
        execution.setVersion(execution.getVersion() + 1);
        return new PreparedInvocation(execution, arguments, tool);
    }

    /** 在工具调用完成后的独立事务中同时终结 TOOL 节点与工具执行行。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProcessAuditEvent persistResult(
            AgentExecutionPort.ClaimedAgentExecution run,
            ToolExecutionEntity candidate,
            ToolExecutionResult result,
            Instant completedAt) {
        ToolExecutionEntity execution =
                toolExecutions.findByToolExecutionId(candidate.getToolExecutionId());
        if (execution == null || !ToolExecutionStatus.EXECUTING.matches(execution.getStatus())) {
            throw new IllegalStateException(ERROR_INVALID_ASSISTANT_CALL);
        }

        // TOOL 节点和工具行共享当前 REQUIRES_NEW 事务，任一失败都会整体回滚。
        ProcessAuditEvent auditEvent =
                conversationProcesses.finishTool(
                        run, execution.getToolProcessItemId(), result, completedAt);
        String status =
                result.status() == ToolExecutionResult.Status.SUCCEEDED
                        ? ToolExecutionStatus.SUCCEEDED.value()
                        : ToolExecutionStatus.FAILED.value();
        if (!toolExecutions.completeExecution(
                execution.getToolExecutionId(),
                execution.getVersion(),
                status,
                completedAt,
                result.failureCode(),
                run.ownerInstanceId(),
                run.executionAttemptNumber())) {
            throw new ExecutionOwnershipLostException(run.executionId());
        }
        return auditEvent;
    }

    private ToolThinkingProcessItem toolItemFor(
            ConversationMessage assistant, ToolExecutionEntity execution) {
        if (assistant == null || execution.getToolProcessItemId() == null) {
            throw new IllegalStateException(ERROR_INVALID_ASSISTANT_CALL);
        }
        if (assistant.requireProcessItem(execution.getToolProcessItemId())
                instanceof ToolThinkingProcessItem toolItem) {
            return toolItem;
        }
        throw new IllegalStateException(ERROR_INVALID_ASSISTANT_CALL);
    }

    private JsonNode argumentsFor(ToolThinkingProcessItem processItem) {
        if (processItem.input().truncated() || processItem.input().value() == null) {
            throw new IllegalStateException(ERROR_INVALID_ASSISTANT_CALL);
        }
        return json.valueToTree(processItem.input().value());
    }

    private static String idempotencyKey(
            AgentExecutionPort.ClaimedAgentExecution run,
            ToolExecutionEntity execution,
            AgentTool tool) {
        if (execution.getIdempotencyKey() != null) {
            return execution.getIdempotencyKey();
        }
        if (tool == null || !tool.supportsIdempotency()) {
            return null;
        }
        return String.join(
                IDEMPOTENCY_SEPARATOR,
                IDEMPOTENCY_PREFIX,
                run.executionId(),
                Long.toString(execution.getModelInvocationSequence()),
                execution.getToolCallId());
    }

    private static boolean isTerminal(String status) {
        return ToolExecutionStatus.SUCCEEDED.matches(status)
                || ToolExecutionStatus.FAILED.matches(status);
    }

    /** 已提交 executing 状态对应的工具、参数和版本快照。 */
    record PreparedInvocation(ToolExecutionEntity execution, JsonNode arguments, AgentTool tool) {}
}
