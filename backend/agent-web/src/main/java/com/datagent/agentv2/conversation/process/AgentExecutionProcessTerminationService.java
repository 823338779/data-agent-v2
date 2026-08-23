package com.datagent.agentv2.conversation.process;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.runtime.ExecutionStoppedException;
import com.datagent.agentv2.conversation.runtime.LocalAgentExecution;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在同一事务中关闭开放过程节点，并把本机执行收敛为终态。 */
@Service
@RequiredArgsConstructor
public class AgentExecutionProcessTerminationService {

    private static final String STATUS_RUNNING = "running";
    private static final String CANCELLATION_CODE = "EXECUTION_CANCELLED";
    private static final String CANCELLATION_MESSAGE = "Execution was cancelled";

    private final AgentExecutionService executions;
    private final ConversationProcessCoordinator processes;
    private final ProcessPayloadSanitizer sanitizer;

    @Transactional
    public void failRunning(
            LocalAgentExecution run, String failureCode, String message, Instant now) {
        AgentExecution execution = executions.lockById(run.executionId());
        if (execution == null || !STATUS_RUNNING.equals(execution.getStatus())) {
            return;
        }
        String safeMessage = sanitizer.sanitizeText(message);
        processes.failOpenItems(run.executionId(), failureCode, safeMessage, now);
        if (!executions.failRunning(
                run.executionId(), execution.getVersion(), failureCode, safeMessage)) {
            throw new ExecutionStoppedException(run.executionId());
        }
    }

    @Transactional
    public void cancelLocked(AgentExecution execution, Instant now) {
        requireRunning(execution);
        processes.failOpenItems(
                execution.getExecutionId(), CANCELLATION_CODE, CANCELLATION_MESSAGE, now);
        if (!executions.cancelRunning(execution.getExecutionId(), execution.getVersion())) {
            throw new ExecutionStoppedException(execution.getExecutionId());
        }
    }

    public void cancelForSteeringLocked(AgentExecution execution, Instant now) {
        cancelLocked(execution, now);
    }

    private static void requireRunning(AgentExecution execution) {
        if (!STATUS_RUNNING.equals(execution.getStatus())) {
            throw new ExecutionStoppedException(execution.getExecutionId());
        }
    }
}
