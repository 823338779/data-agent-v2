package com.datagent.agentv2.conversation.context;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.AgentExecutionEvent;
import com.datagent.agentv2.conversation.enums.ExecutionEventType;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import com.datagent.agentv2.conversation.exception.ExecutionDeadlineExceededException;
import com.datagent.agentv2.conversation.exception.ExecutionOwnershipLostException;
import com.datagent.agentv2.conversation.process.AgentExecutionProcessTerminationService;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.persistence.service.AgentExecutionEventService;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.platform.context.TenantContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 以共享数据库行锁实现 LSM execution fencing、取消、事件和失败收敛。 */
@Component
public class WebContextExecutionControlAdapter implements ContextExecutionControlPort {

    private static final String EVENT_ID_PREFIX = "execution_event";
    private static final String EMPTY_EVENT_PAYLOAD = "{}";

    private final AgentExecutionService executions;
    private final AgentExecutionProcessTerminationService termination;
    private final ConversationSessionService sessions;
    private final AgentExecutionEventService events;
    private final IdGenerator ids;

    public WebContextExecutionControlAdapter(
            AgentExecutionService executions,
            AgentExecutionProcessTerminationService termination,
            ConversationSessionService sessions,
            AgentExecutionEventService events,
            IdGenerator ids) {
        this.executions = executions;
        this.termination = termination;
        this.sessions = sessions;
        this.events = events;
        this.ids = ids;
    }

    @Override
    public void assertOwnedAndWithinBudget(ContextExecutionRef execution) {
        // select for update 加入调用方事务；publication 的 REQUIRES_NEW 会把锁保持到 manifest CAS 后提交。
        AgentExecution locked = lockAndValidate(execution);
        Instant deadline = locked.getHardDeadlineAt();
        if (deadline == null || !deadline.isAfter(Instant.now())) {
            throw new ExecutionDeadlineExceededException(execution.executionId());
        }
    }

    @Override
    public boolean cancelIfRequested(ContextExecutionRef execution) {
        AgentExecution locked = lockAndValidate(execution);
        if (locked.getCancellationRequestedAt() == null) {
            return false;
        }
        return termination.cancelOwned(run(execution), Instant.now());
    }

    @Override
    @Transactional
    public void publish(ContextExecutionRef execution, ContextLifecycleEvent event) {
        lockAndValidate(execution);
        ExecutionEventType type =
                switch (event.type()) {
                    case STARTED -> ExecutionEventType.CONTEXT_COMPACTION_STARTED;
                    case COMPLETED -> ExecutionEventType.CONTEXT_COMPACTION_COMPLETED;
                    case FAILED -> ExecutionEventType.CONTEXT_COMPACTION_FAILED;
                };
        persistBodyFreeEvent(execution, type, Instant.now());
    }

    @Override
    @Transactional
    public void fail(ContextExecutionRef execution, String failureCode, String safeMessage) {
        lockAndValidate(execution);
        Instant now = Instant.now();
        persistBodyFreeEvent(execution, ExecutionEventType.CONTEXT_COMPACTION_FAILED, now);
        termination.failOwned(run(execution), failureCode, safeMessage, now);
    }

    private AgentExecution lockAndValidate(ContextExecutionRef execution) {
        if (!TenantContext.requiredTenantId().equals(execution.tenantId())) {
            throw new ExecutionOwnershipLostException(execution.executionId());
        }
        AgentExecution locked = executions.lockById(execution.executionId());
        if (locked == null
                || !execution.conversationSessionId().equals(locked.getConversationSessionId())
                || !ExecutionStatus.RUNNING.value().equals(locked.getStatus())
                || !execution.ownerInstanceId().equals(locked.getOwnerInstanceId())
                || execution.executionAttempt() != locked.getExecutionAttemptNumber()) {
            throw new ExecutionOwnershipLostException(execution.executionId());
        }
        return locked;
    }

    private void persistBodyFreeEvent(
            ContextExecutionRef execution, ExecutionEventType type, Instant now) {
        events.save(
                AgentExecutionEvent.occurred(
                        ids.next(EVENT_ID_PREFIX),
                        execution.conversationSessionId(),
                        execution.executionId(),
                        sessions.nextExecutionEventSequence(execution.conversationSessionId()),
                        type.value(),
                        EMPTY_EVENT_PAYLOAD,
                        now));
    }

    private static AgentExecutionPort.ClaimedAgentExecution run(ContextExecutionRef execution) {
        return new AgentExecutionPort.ClaimedAgentExecution(
                execution.tenantId(),
                execution.executionId(),
                execution.ownerInstanceId(),
                execution.executionAttempt());
    }
}
