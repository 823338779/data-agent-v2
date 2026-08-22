package com.datagent.agentv2.conversation.runtime;

import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ID_PREFIX_EXECUTION_EVENT;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.AgentExecutionEvent;
import com.datagent.agentv2.conversation.enums.ExecutionEventType;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import com.datagent.agentv2.persistence.entity.AgentExecutionAttemptEntity;
import com.datagent.agentv2.persistence.service.AgentExecutionAttemptService;
import com.datagent.agentv2.persistence.service.AgentExecutionEventService;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.persistence.service.UserAccountService;
import com.datagent.agentv2.platform.context.TenantContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 承载执行领取的短事务，确保数据库提交完成后才注册本地 owner 并启动 worker。 */
@Service
@RequiredArgsConstructor
public class AgentExecutionClaimTransactionService {

    private static final int MAX_CONCURRENT_EXECUTIONS_PER_USER = 3;

    private final AgentExecutionService agentExecutions;
    private final AgentExecutionAttemptService attempts;
    private final AgentExecutionEventService events;
    private final ConversationSessionService sessions;
    private final UserAccountService users;
    private final IdGenerator ids;
    private final AgentExecutionRuntimeProperties properties;
    private final ExecutionWorkerIdentity identity;

    /** 锁定用户容量并领取其最早排队执行，所有领取记录在同一独立事务中提交。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentExecutionPort.ClaimedAgentExecution claimForUser(String userId) {
        // 通过用户行锁串行化跨实例的单用户并发额度检查。
        if (users.lockById(userId) == null
                || agentExecutions.countRunningExecutionsForUser(userId)
                        >= MAX_CONCURRENT_EXECUTIONS_PER_USER) {
            return null;
        }
        AgentExecution run = agentExecutions.lockOldestQueuedForUser(userId);
        if (run == null) {
            return null;
        }

        // 计算本次 attempt 的租约和剩余硬截止时间，并原子领取执行。
        Instant now = Instant.now();
        long executionAttemptNumber = run.getExecutionAttemptNumber() + 1;
        long configuredBudget = properties.executionBudget().toMillis();
        long executionBudget =
                run.getExecutionBudgetMs() == null ? configuredBudget : run.getExecutionBudgetMs();
        long activeExecution = run.getActiveExecutionMs() == null ? 0L : run.getActiveExecutionMs();
        long remainingBudget = Math.max(1L, executionBudget - activeExecution);
        Instant leaseExpiresAt = now.plus(properties.leaseDuration());
        Instant hardDeadlineAt = now.plusMillis(remainingBudget);
        if (!agentExecutions.claimQueued(
                run,
                executionAttemptNumber,
                identity.workerInstanceId(),
                now,
                leaseExpiresAt,
                hardDeadlineAt)) {
            return null;
        }

        // 同事务写入 attempt 和 started 事件，避免 worker 观察到不完整的领取状态。
        AgentExecutionAttemptEntity attempt = new AgentExecutionAttemptEntity();
        attempt.setExecutionId(run.getExecutionId());
        attempt.setExecutionAttemptNumber(executionAttemptNumber);
        attempt.setOwnerInstanceId(identity.workerInstanceId());
        attempt.setStatus(ExecutionStatus.RUNNING.value());
        attempt.setStartedAt(now);
        attempt.setVersion(0L);
        attempts.insert(attempt);

        events.save(
                AgentExecutionEvent.occurred(
                        ids.next(ID_PREFIX_EXECUTION_EVENT),
                        run.getConversationSessionId(),
                        run.getExecutionId(),
                        sessions.nextExecutionEventSequence(run.getConversationSessionId()),
                        ExecutionEventType.STARTED.value(),
                        null,
                        now));

        return AgentExecutionPort.ClaimedAgentExecution.composeFromLease(
                TenantContext.requiredTenantId(),
                run,
                identity.workerInstanceId(),
                executionAttemptNumber);
    }
}
