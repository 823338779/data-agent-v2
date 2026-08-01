package com.datagent.agentv2.conversation.runtime;

import static com.datagent.agentv2.conversation.AgentExecutionConstants.FAILURE_EXECUTION_DEADLINE_EXCEEDED;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.FAILURE_LEASE_EXPIRED;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.FAILURE_RECOVERY_EXHAUSTED;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.RESUME_LEASE_TAKEOVER;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.STATUS_CANCELLED;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.STATUS_FAILED;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.STATUS_LOST;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.STATUS_RUNNING;

import com.datagent.agentv2.persistence.entity.AgentExecutionAttemptEntity;
import com.datagent.agentv2.persistence.entity.AgentExecutionEntity;
import com.datagent.agentv2.persistence.service.AgentExecutionAttemptService;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class AgentExecutionLeaseService {

    private static final int MAX_INFRASTRUCTURE_RECOVERY_ATTEMPTS = 3;

    private final AgentExecutionService agentExecutions;
    private final AgentExecutionAttemptService attempts;
    private final AgentExecutionRuntimeProperties properties;
    private final TransactionTemplate transactions;

    public AgentExecutionLeaseService(
            AgentExecutionService agentExecutions,
            AgentExecutionAttemptService attempts,
            AgentExecutionRuntimeProperties properties,
            PlatformTransactionManager transactionManager) {
        this.agentExecutions = agentExecutions;
        this.attempts = attempts;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public boolean heartbeat(AgentExecutionPort.ClaimedAgentExecution run) {
        Instant now = Instant.now();
        return agentExecutions.renewLease(
                run.executionId(),
                run.ownerInstanceId(),
                run.executionAttemptNumber(),
                now,
                now.plus(properties.leaseDuration()));
    }

    public void assertOwnedAndWithinBudget(AgentExecutionPort.ClaimedAgentExecution run) {
        Instant now = Instant.now();
        if (agentExecutions.fenceOwned(
                run.executionId(), run.ownerInstanceId(), run.executionAttemptNumber(), now)) {
            return;
        }
        if (agentExecutions.failOwnedPastDeadline(
                run.executionId(), run.ownerInstanceId(), run.executionAttemptNumber(), now)) {
            attempts.finish(
                    run.executionId(),
                    run.executionAttemptNumber(),
                    STATUS_FAILED,
                    now,
                    FAILURE_EXECUTION_DEADLINE_EXCEEDED);
            throw new ExecutionDeadlineExceededException(run.executionId());
        }
        throw new ExecutionOwnershipLostException(run.executionId());
    }

    public boolean cancellationRequested(AgentExecutionPort.ClaimedAgentExecution run) {
        return agentExecutions.cancellationRequested(
                run.executionId(), run.ownerInstanceId(), run.executionAttemptNumber());
    }

    public boolean cancelIfRequested(AgentExecutionPort.ClaimedAgentExecution run) {
        Instant now = Instant.now();
        if (!agentExecutions.cancelOwnedIfRequested(
                run.executionId(), run.ownerInstanceId(), run.executionAttemptNumber(), now)) {
            return false;
        }
        attempts.finish(
                run.executionId(), run.executionAttemptNumber(), STATUS_CANCELLED, now, null);
        return true;
    }

    public RecoveryResult takeoverNext(String newOwnerInstanceId) {
        return Objects.requireNonNull(
                transactions.execute(ignored -> takeoverLocked(newOwnerInstanceId)));
    }

    private RecoveryResult takeoverLocked(String newOwnerInstanceId) {
        Instant now = Instant.now();
        AgentExecutionEntity run = agentExecutions.lockNextExpired(now);
        if (run == null) {
            return RecoveryResult.none();
        }
        long accumulated = accumulatedAt(run, now);
        long budget = run.getExecutionBudgetMs();
        if (run.getRecoveryAttemptCount() >= MAX_INFRASTRUCTURE_RECOVERY_ATTEMPTS) {
            if (agentExecutions.failExpiredRecovery(run, accumulated, now)) {
                attempts.finish(
                        run.getExecutionId(),
                        run.getExecutionAttemptNumber(),
                        STATUS_FAILED,
                        now,
                        FAILURE_RECOVERY_EXHAUSTED);
                return RecoveryResult.terminal();
            }
            return RecoveryResult.none();
        }
        if (accumulated >= budget) {
            if (agentExecutions.failExpiredBudget(run, accumulated, now)) {
                attempts.finish(
                        run.getExecutionId(),
                        run.getExecutionAttemptNumber(),
                        STATUS_FAILED,
                        now,
                        FAILURE_EXECUTION_DEADLINE_EXCEEDED);
                return RecoveryResult.terminal();
            }
            return RecoveryResult.none();
        }

        long nextAttempt = run.getExecutionAttemptNumber() + 1;
        Instant hardDeadline = now.plusMillis(budget - accumulated);
        if (!agentExecutions.takeoverExpired(
                run,
                newOwnerInstanceId,
                nextAttempt,
                accumulated,
                now,
                now.plus(properties.leaseDuration()),
                hardDeadline)) {
            return RecoveryResult.none();
        }
        attempts.finish(
                run.getExecutionId(),
                run.getExecutionAttemptNumber(),
                STATUS_LOST,
                now,
                FAILURE_LEASE_EXPIRED);

        AgentExecutionAttemptEntity attempt = new AgentExecutionAttemptEntity();
        attempt.setExecutionId(run.getExecutionId());
        attempt.setExecutionAttemptNumber(nextAttempt);
        attempt.setOwnerInstanceId(newOwnerInstanceId);
        attempt.setStatus(STATUS_RUNNING);
        attempt.setResumeReason(RESUME_LEASE_TAKEOVER);
        attempt.setStartedAt(now);
        attempt.setVersion(0L);
        attempts.insert(attempt);
        return RecoveryResult.claimed(
                new AgentExecutionPort.ClaimedAgentExecution(
                        run.getTenantId(), run.getExecutionId(), newOwnerInstanceId, nextAttempt));
    }

    private static long accumulatedAt(AgentExecutionEntity run, Instant now) {
        long previous = run.getActiveExecutionMs() == null ? 0L : run.getActiveExecutionMs();
        if (run.getActiveSegmentStartedAt() == null) {
            return previous;
        }
        return previous
                + Math.max(0L, Duration.between(run.getActiveSegmentStartedAt(), now).toMillis());
    }

    /** 返回恢复后可继续执行的已领取执行，以及是否已直接收敛为终态。 */
    public record RecoveryResult(
            AgentExecutionPort.ClaimedAgentExecution execution, boolean terminalized) {

        static RecoveryResult none() {
            return new RecoveryResult(null, false);
        }

        static RecoveryResult terminal() {
            return new RecoveryResult(null, true);
        }

        static RecoveryResult claimed(AgentExecutionPort.ClaimedAgentExecution run) {
            return new RecoveryResult(run, false);
        }
    }
}
