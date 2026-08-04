package com.datagent.agentv2.conversation.process;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import com.datagent.agentv2.conversation.exception.ExecutionOwnershipLostException;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.persistence.service.AgentExecutionAttemptService;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在同一数据库事务中收敛执行终态及其 assistant 中心消息中的全部开放过程节点。 */
@Service
public class AgentExecutionProcessTerminationService {

    private static final String CANCELLATION_PROCESS_CODE = "EXECUTION_CANCELLED";
    private static final String CANCELLATION_PROCESS_MESSAGE = "Execution was cancelled";

    private final AgentExecutionService executions;
    private final AgentExecutionAttemptService attempts;
    private final ConversationProcessCoordinator processes;
    private final ProcessPayloadSanitizer sanitizer;

    public AgentExecutionProcessTerminationService(
            AgentExecutionService executions,
            AgentExecutionAttemptService attempts,
            ConversationProcessCoordinator processes,
            ProcessPayloadSanitizer sanitizer) {
        this.executions = executions;
        this.attempts = attempts;
        this.processes = processes;
        this.sanitizer = sanitizer;
    }

    /** 响应已持久化的取消请求；只有当前 owner 能关闭节点并提交取消终态。 */
    @Transactional
    public boolean cancelOwned(AgentExecutionPort.ClaimedAgentExecution run, Instant now) {
        AgentExecution execution = requireOwnedLocked(run);
        if (execution.getCancellationRequestedAt() == null) {
            return false;
        }
        closeOpenItems(
                execution.getExecutionId(),
                CANCELLATION_PROCESS_CODE,
                CANCELLATION_PROCESS_MESSAGE,
                now);
        if (!executions.cancelOwnedIfRequested(
                run.executionId(), run.ownerInstanceId(), run.executionAttemptNumber(), now)) {
            throw new ExecutionOwnershipLostException(run.executionId());
        }
        finishAttempt(execution, ExecutionStatus.CANCELLED.value(), null, now);
        return true;
    }

    /** 将当前 owner 遇到的非 fencing 异常与全部开放节点原子收敛为失败。 */
    @Transactional
    public void failOwned(
            AgentExecutionPort.ClaimedAgentExecution run,
            String failureCode,
            String safeMessage,
            Instant now) {
        AgentExecution execution = requireOwnedLocked(run);
        String sanitizedMessage = sanitizer.sanitizeText(safeMessage);
        closeOpenItems(execution.getExecutionId(), failureCode, sanitizedMessage, now);
        if (!executions.failOwned(
                run.executionId(),
                run.ownerInstanceId(),
                run.executionAttemptNumber(),
                failureCode,
                sanitizedMessage,
                now)) {
            throw new ExecutionOwnershipLostException(run.executionId());
        }
        finishAttempt(execution, ExecutionStatus.FAILED.value(), failureCode, now);
    }

    /** 处理已由调用方锁定的预算或恢复耗尽执行，不再要求过期 owner 仍持有租约。 */
    @Transactional
    public void closeExpiredLocked(
            AgentExecution execution,
            String failureCode,
            String safeMessage,
            long accumulatedExecutionMs,
            Instant now) {
        failLocked(execution, failureCode, safeMessage, accumulatedExecutionMs, now);
    }

    /** 直接取消尚未进入 worker 的锁定执行，并兼容未来可能提前生成的过程节点。 */
    @Transactional
    public void cancelLocked(AgentExecution execution, Instant now) {
        closeOpenItems(
                execution.getExecutionId(),
                CANCELLATION_PROCESS_CODE,
                CANCELLATION_PROCESS_MESSAGE,
                now);
        if (!executions.cancelLocked(execution, now)) {
            throw new ExecutionOwnershipLostException(execution.getExecutionId());
        }
        finishAttempt(execution, ExecutionStatus.CANCELLED.value(), null, now);
    }

    private void failLocked(
            AgentExecution execution,
            String failureCode,
            String message,
            Long accumulatedExecutionMs,
            Instant now) {
        String safeMessage = sanitizer.sanitizeText(message);
        closeOpenItems(execution.getExecutionId(), failureCode, safeMessage, now);
        if (!executions.failLocked(
                execution,
                failureCode,
                safeMessage,
                accumulatedExecutionMs == null ? 0L : accumulatedExecutionMs,
                now)) {
            throw new ExecutionOwnershipLostException(execution.getExecutionId());
        }
        finishAttempt(execution, ExecutionStatus.FAILED.value(), failureCode, now);
    }

    private AgentExecution requireOwnedLocked(AgentExecutionPort.ClaimedAgentExecution run) {
        AgentExecution execution = executions.lockById(run.executionId());
        if (execution == null
                || !ExecutionStatus.RUNNING.value().equals(execution.getStatus())
                || !run.ownerInstanceId().equals(execution.getOwnerInstanceId())
                || run.executionAttemptNumber() != execution.getExecutionAttemptNumber()) {
            throw new ExecutionOwnershipLostException(run.executionId());
        }
        return execution;
    }

    private void closeOpenItems(
            String executionId, String failureCode, String safeMessage, Instant now) {
        processes.failOpenItems(executionId, failureCode, safeMessage, now);
    }

    private void finishAttempt(
            AgentExecution execution, String status, String failureCode, Instant now) {
        if (execution.getExecutionAttemptNumber() > 0L) {
            attempts.finish(
                    execution.getExecutionId(),
                    execution.getExecutionAttemptNumber(),
                    status,
                    now,
                    failureCode);
        }
    }
}
