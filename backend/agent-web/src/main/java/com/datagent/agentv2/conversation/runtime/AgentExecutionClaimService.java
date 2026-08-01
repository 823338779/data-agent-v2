package com.datagent.agentv2.conversation.runtime;

import static com.datagent.agentv2.conversation.AgentExecutionConstants.ERROR_EXECUTION_ALREADY_OWNED_PREFIX;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.EVENT_EXECUTION_STARTED;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.ID_PREFIX_EXECUTION_EVENT;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.STATUS_RUNNING;

import com.datagent.agentv2.persistence.entity.AgentExecutionAttemptEntity;
import com.datagent.agentv2.persistence.entity.AgentExecutionEntity;
import com.datagent.agentv2.persistence.entity.AgentExecutionEventEntity;
import com.datagent.agentv2.persistence.service.AgentExecutionAttemptService;
import com.datagent.agentv2.persistence.service.AgentExecutionEventService;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.persistence.service.UserAccountService;
import com.datagent.agentv2.platform.context.TenantContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class AgentExecutionClaimService {

    private static final int MAX_CONCURRENT_EXECUTIONS_PER_USER = 3;
    private static final int USER_SCAN_LIMIT = 256;

    private final AgentExecutionService agentExecutions;
    private final AgentExecutionAttemptService attempts;
    private final AgentExecutionEventService events;
    private final ConversationSessionService sessions;
    private final UserAccountService users;
    private final IdGenerator ids;
    private final TransactionTemplate transactions;
    private final AgentExecutionRuntimeProperties properties;
    private final ExecutionWorkerCapacity capacity;
    private final ExecutionOwnerRegistry owners;
    private final AgentExecutionPort execution;
    private final ExecutionWorkerIdentity identity;

    public AgentExecutionClaimService(
            AgentExecutionService agentExecutions,
            AgentExecutionAttemptService attempts,
            AgentExecutionEventService events,
            ConversationSessionService sessions,
            UserAccountService users,
            IdGenerator ids,
            PlatformTransactionManager transactionManager,
            AgentExecutionRuntimeProperties properties,
            ExecutionWorkerCapacity capacity,
            ExecutionOwnerRegistry owners,
            AgentExecutionPort execution,
            ExecutionWorkerIdentity identity) {
        this.agentExecutions = agentExecutions;
        this.attempts = attempts;
        this.events = events;
        this.sessions = sessions;
        this.users = users;
        this.ids = ids;
        this.transactions = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.capacity = capacity;
        this.owners = owners;
        this.execution = execution;
        this.identity = identity;
    }

    public boolean claimNext() {
        for (String userId : agentExecutions.queuedOwnerUserIds(USER_SCAN_LIMIT)) {
            Optional<ExecutionWorkerCapacity.Permit> acquired = capacity.tryAcquire();
            if (acquired.isEmpty()) {
                return false;
            }
            ExecutionWorkerCapacity.Permit permit = acquired.orElseThrow();
            AgentExecutionPort.ClaimedAgentExecution claimed;
            try {
                claimed = transactions.execute(ignored -> claimForUser(userId));
            } catch (RuntimeException failure) {
                permit.close();
                throw failure;
            }
            if (claimed == null) {
                permit.close();
                continue;
            }
            if (!owners.register(claimed, permit)) {
                throw new IllegalStateException(
                        ERROR_EXECUTION_ALREADY_OWNED_PREFIX + claimed.executionId());
            }
            try {
                execution.start(claimed, () -> owners.release(claimed.executionId()));
            } catch (RuntimeException failure) {
                owners.release(claimed.executionId());
                throw failure;
            }
            return true;
        }
        return false;
    }

    public String instanceId() {
        return identity.workerInstanceId();
    }

    private AgentExecutionPort.ClaimedAgentExecution claimForUser(String userId) {
        if (users.lockById(userId) == null
                || agentExecutions.countRunningExecutionsForUser(userId)
                        >= MAX_CONCURRENT_EXECUTIONS_PER_USER) {
            return null;
        }
        AgentExecutionEntity run = agentExecutions.lockOldestQueuedForUser(userId);
        if (run == null) {
            return null;
        }

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

        AgentExecutionAttemptEntity attempt = new AgentExecutionAttemptEntity();
        attempt.setExecutionId(run.getExecutionId());
        attempt.setExecutionAttemptNumber(executionAttemptNumber);
        attempt.setOwnerInstanceId(identity.workerInstanceId());
        attempt.setStatus(STATUS_RUNNING);
        attempt.setStartedAt(now);
        attempt.setVersion(0L);
        attempts.insert(attempt);

        AgentExecutionEventEntity event = new AgentExecutionEventEntity();
        event.setExecutionEventId(ids.next(ID_PREFIX_EXECUTION_EVENT));
        event.setConversationSessionId(run.getConversationSessionId());
        event.setExecutionId(run.getExecutionId());
        event.setExecutionEventSequence(
                sessions.nextExecutionEventSequence(run.getConversationSessionId()));
        event.setType(EVENT_EXECUTION_STARTED);
        event.setOccurredAt(now);
        event.setVersion(0L);
        events.save(event);

        return new AgentExecutionPort.ClaimedAgentExecution(
                TenantContext.requiredTenantId(),
                run.getExecutionId(),
                identity.workerInstanceId(),
                executionAttemptNumber);
    }
}
