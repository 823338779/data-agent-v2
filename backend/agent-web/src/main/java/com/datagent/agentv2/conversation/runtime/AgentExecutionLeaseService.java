package com.datagent.agentv2.conversation.runtime;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.execution.ExecutionFailureCode;
import com.datagent.agentv2.conversation.execution.ExecutionResumeReason;
import com.datagent.agentv2.conversation.execution.ExecutionStatus;
import com.datagent.agentv2.persistence.entity.AgentExecutionAttemptEntity;
import com.datagent.agentv2.persistence.service.AgentExecutionAttemptService;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentExecutionLeaseService {

    private static final int MAX_INFRASTRUCTURE_RECOVERY_ATTEMPTS = 3;

    private final AgentExecutionService agentExecutions;
    private final AgentExecutionAttemptService attempts;
    private final AgentExecutionRuntimeProperties properties;

    public AgentExecutionLeaseService(
            AgentExecutionService agentExecutions,
            AgentExecutionAttemptService attempts,
            AgentExecutionRuntimeProperties properties) {
        this.agentExecutions = agentExecutions;
        this.attempts = attempts;
        this.properties = properties;
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
                    ExecutionStatus.FAILED.value(),
                    now,
                    ExecutionFailureCode.DEADLINE_EXCEEDED.value());
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
                run.executionId(),
                run.executionAttemptNumber(),
                ExecutionStatus.CANCELLED.value(),
                now,
                null);
        return true;
    }

    @Transactional
    public RecoveryResult takeoverNext(String newOwnerInstanceId) {
        return takeoverLocked(newOwnerInstanceId);
    }

    private RecoveryResult takeoverLocked(String newOwnerInstanceId) {
        Instant now = Instant.now();
        AgentExecution run = agentExecutions.lockNextExpired(now);
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
                        ExecutionStatus.FAILED.value(),
                        now,
                        ExecutionFailureCode.RECOVERY_EXHAUSTED.value());
                return RecoveryResult.terminal();
            }
            return RecoveryResult.none();
        }
        if (accumulated >= budget) {
            if (agentExecutions.failExpiredBudget(run, accumulated, now)) {
                attempts.finish(
                        run.getExecutionId(),
                        run.getExecutionAttemptNumber(),
                        ExecutionStatus.FAILED.value(),
                        now,
                        ExecutionFailureCode.DEADLINE_EXCEEDED.value());
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
                ExecutionStatus.LOST.value(),
                now,
                ExecutionFailureCode.LEASE_EXPIRED.value());

        AgentExecutionAttemptEntity attempt = new AgentExecutionAttemptEntity();
        attempt.setExecutionId(run.getExecutionId());
        attempt.setExecutionAttemptNumber(nextAttempt);
        attempt.setOwnerInstanceId(newOwnerInstanceId);
        attempt.setStatus(ExecutionStatus.RUNNING.value());
        attempt.setResumeReason(ExecutionResumeReason.LEASE_TAKEOVER.name());
        attempt.setStartedAt(now);
        attempt.setVersion(0L);
        attempts.insert(attempt);
        return RecoveryResult.claimed(
                new AgentExecutionPort.ClaimedAgentExecution(
                        TenantContext.requiredTenantId(),
                        run.getExecutionId(),
                        newOwnerInstanceId,
                        nextAttempt));
    }

    private static long accumulatedAt(AgentExecution run, Instant now) {
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
