package com.datagent.agentv2.conversation.runtime;

import static com.datagent.agentv2.conversation.RunConstants.ERROR_RUN_ALREADY_OWNED_PREFIX;
import static com.datagent.agentv2.conversation.RunConstants.EVENT_RUN_STARTED;
import static com.datagent.agentv2.conversation.RunConstants.ID_PREFIX_EVENT;
import static com.datagent.agentv2.conversation.RunConstants.STATUS_RUNNING;

import com.datagent.agentv2.persistence.entity.AgentRunAttemptEntity;
import com.datagent.agentv2.persistence.entity.AgentRunEntity;
import com.datagent.agentv2.persistence.entity.AgentRunEventEntity;
import com.datagent.agentv2.persistence.service.AgentRunAttemptService;
import com.datagent.agentv2.persistence.service.AgentRunEventService;
import com.datagent.agentv2.persistence.service.AgentRunService;
import com.datagent.agentv2.persistence.service.AgentSessionService;
import com.datagent.agentv2.persistence.service.UserAccountService;
import com.datagent.agentv2.platform.context.TenantContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class RunClaimService {

    private static final int MAX_RUNNING_RUNS_PER_USER = 3;
    private static final int USER_SCAN_LIMIT = 256;

    private final AgentRunService runs;
    private final AgentRunAttemptService attempts;
    private final AgentRunEventService events;
    private final AgentSessionService sessions;
    private final UserAccountService users;
    private final IdGenerator ids;
    private final TransactionTemplate transactions;
    private final RunRuntimeProperties properties;
    private final InstanceRunCapacity capacity;
    private final RunOwnerRegistry owners;
    private final RunExecutionPort execution;
    private final RunInstanceIdentity identity;

    public RunClaimService(
            AgentRunService runs,
            AgentRunAttemptService attempts,
            AgentRunEventService events,
            AgentSessionService sessions,
            UserAccountService users,
            IdGenerator ids,
            PlatformTransactionManager transactionManager,
            RunRuntimeProperties properties,
            InstanceRunCapacity capacity,
            RunOwnerRegistry owners,
            RunExecutionPort execution,
            RunInstanceIdentity identity) {
        this.runs = runs;
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
        for (String userId : runs.queuedOwnerUserIds(USER_SCAN_LIMIT)) {
            Optional<InstanceRunCapacity.Permit> acquired = capacity.tryAcquire();
            if (acquired.isEmpty()) {
                return false;
            }
            InstanceRunCapacity.Permit permit = acquired.orElseThrow();
            RunExecutionPort.OwnedRun claimed;
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
                throw new IllegalStateException(ERROR_RUN_ALREADY_OWNED_PREFIX + claimed.runId());
            }
            try {
                execution.start(claimed, () -> owners.release(claimed.runId()));
            } catch (RuntimeException failure) {
                owners.release(claimed.runId());
                throw failure;
            }
            return true;
        }
        return false;
    }

    public String instanceId() {
        return identity.id();
    }

    private RunExecutionPort.OwnedRun claimForUser(String userId) {
        if (users.lockById(userId) == null
                || runs.countRunningForUser(userId) >= MAX_RUNNING_RUNS_PER_USER) {
            return null;
        }
        AgentRunEntity run = runs.lockOldestQueuedForUser(userId);
        if (run == null) {
            return null;
        }

        Instant now = Instant.now();
        long executionAttempt = run.getExecutionAttempt() + 1;
        long configuredBudget = properties.runExecutionBudget().toMillis();
        long executionBudget =
                run.getExecutionBudgetMs() == null ? configuredBudget : run.getExecutionBudgetMs();
        long activeExecution = run.getActiveExecutionMs() == null ? 0L : run.getActiveExecutionMs();
        long remainingBudget = Math.max(1L, executionBudget - activeExecution);
        Instant leaseExpiresAt = now.plus(properties.leaseDuration());
        Instant hardDeadlineAt = now.plusMillis(remainingBudget);
        if (!runs.claimQueued(
                run, executionAttempt, identity.id(), now, leaseExpiresAt, hardDeadlineAt)) {
            return null;
        }

        AgentRunAttemptEntity attempt = new AgentRunAttemptEntity();
        attempt.setRunId(run.getUuid());
        attempt.setExecutionAttempt(executionAttempt);
        attempt.setOwnerInstanceId(identity.id());
        attempt.setStatus(STATUS_RUNNING);
        attempt.setStartedAt(now);
        attempt.setVersion(0L);
        attempts.insert(attempt);

        AgentRunEventEntity event = new AgentRunEventEntity();
        event.setUuid(ids.next(ID_PREFIX_EVENT));
        event.setSessionId(run.getSessionId());
        event.setRunId(run.getUuid());
        event.setSequence(sessions.nextEventSequence(run.getSessionId()));
        event.setType(EVENT_RUN_STARTED);
        event.setOccurredAt(now);
        event.setVersion(0L);
        events.save(event);

        return new RunExecutionPort.OwnedRun(
                TenantContext.requiredTenantId(), run.getUuid(), identity.id(), executionAttempt);
    }
}
