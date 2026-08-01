package com.datagent.agentv2.conversation.runtime;

import static com.datagent.agentv2.conversation.RunConstants.FAILURE_LEASE_EXPIRED;
import static com.datagent.agentv2.conversation.RunConstants.FAILURE_RUN_DEADLINE_EXCEEDED;
import static com.datagent.agentv2.conversation.RunConstants.RESUME_LEASE_TAKEOVER;
import static com.datagent.agentv2.conversation.RunConstants.STATUS_CANCELLED;
import static com.datagent.agentv2.conversation.RunConstants.STATUS_FAILED;
import static com.datagent.agentv2.conversation.RunConstants.STATUS_LOST;
import static com.datagent.agentv2.conversation.RunConstants.STATUS_RUNNING;

import com.datagent.agentv2.persistence.entity.AgentRunAttemptEntity;
import com.datagent.agentv2.persistence.entity.AgentRunEntity;
import com.datagent.agentv2.persistence.service.AgentRunAttemptService;
import com.datagent.agentv2.persistence.service.AgentRunService;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class RunLeaseService {

    private final AgentRunService runs;
    private final AgentRunAttemptService attempts;
    private final RunRuntimeProperties properties;
    private final TransactionTemplate transactions;

    public RunLeaseService(
            AgentRunService runs,
            AgentRunAttemptService attempts,
            RunRuntimeProperties properties,
            PlatformTransactionManager transactionManager) {
        this.runs = runs;
        this.attempts = attempts;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public boolean heartbeat(RunExecutionPort.OwnedRun run) {
        Instant now = Instant.now();
        return runs.renewLease(
                run.runId(),
                run.ownerInstanceId(),
                run.executionAttempt(),
                now,
                now.plus(properties.leaseDuration()));
    }

    public void assertOwnedAndWithinBudget(RunExecutionPort.OwnedRun run) {
        Instant now = Instant.now();
        if (runs.fenceOwned(run.runId(), run.ownerInstanceId(), run.executionAttempt(), now)) {
            return;
        }
        if (runs.failOwnedPastDeadline(
                run.runId(), run.ownerInstanceId(), run.executionAttempt(), now)) {
            attempts.finish(
                    run.runId(),
                    run.executionAttempt(),
                    STATUS_FAILED,
                    now,
                    FAILURE_RUN_DEADLINE_EXCEEDED);
            throw new RunDeadlineExceededException(run.runId());
        }
        throw new RunOwnershipLostException(run.runId());
    }

    public boolean cancellationRequested(RunExecutionPort.OwnedRun run) {
        return runs.cancellationRequested(
                run.runId(), run.ownerInstanceId(), run.executionAttempt());
    }

    public boolean cancelIfRequested(RunExecutionPort.OwnedRun run) {
        Instant now = Instant.now();
        if (!runs.cancelOwnedIfRequested(
                run.runId(), run.ownerInstanceId(), run.executionAttempt(), now)) {
            return false;
        }
        attempts.finish(run.runId(), run.executionAttempt(), STATUS_CANCELLED, now, null);
        return true;
    }

    public RecoveryResult takeoverNext(String newOwnerInstanceId) {
        return Objects.requireNonNull(
                transactions.execute(ignored -> takeoverLocked(newOwnerInstanceId)));
    }

    private RecoveryResult takeoverLocked(String newOwnerInstanceId) {
        Instant now = Instant.now();
        AgentRunEntity run = runs.lockNextExpired(now);
        if (run == null) {
            return RecoveryResult.none();
        }
        long accumulated = accumulatedAt(run, now);
        long budget = run.getExecutionBudgetMs();
        if (accumulated >= budget) {
            if (runs.failExpiredBudget(run, accumulated, now)) {
                attempts.finish(
                        run.getRunId(),
                        run.getExecutionAttempt(),
                        STATUS_FAILED,
                        now,
                        FAILURE_RUN_DEADLINE_EXCEEDED);
                return RecoveryResult.terminal();
            }
            return RecoveryResult.none();
        }

        long nextAttempt = run.getExecutionAttempt() + 1;
        Instant hardDeadline = now.plusMillis(budget - accumulated);
        if (!runs.takeoverExpired(
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
                run.getRunId(), run.getExecutionAttempt(), STATUS_LOST, now, FAILURE_LEASE_EXPIRED);

        AgentRunAttemptEntity attempt = new AgentRunAttemptEntity();
        attempt.setRunId(run.getRunId());
        attempt.setExecutionAttempt(nextAttempt);
        attempt.setOwnerInstanceId(newOwnerInstanceId);
        attempt.setStatus(STATUS_RUNNING);
        attempt.setResumeReason(RESUME_LEASE_TAKEOVER);
        attempt.setStartedAt(now);
        attempt.setVersion(0L);
        attempts.insert(attempt);
        return RecoveryResult.claimed(
                new RunExecutionPort.OwnedRun(
                        run.getTenantId(), run.getRunId(), newOwnerInstanceId, nextAttempt));
    }

    private static long accumulatedAt(AgentRunEntity run, Instant now) {
        long previous = run.getActiveExecutionMs() == null ? 0L : run.getActiveExecutionMs();
        if (run.getActiveSegmentStartedAt() == null) {
            return previous;
        }
        return previous
                + Math.max(0L, Duration.between(run.getActiveSegmentStartedAt(), now).toMillis());
    }

    public record RecoveryResult(RunExecutionPort.OwnedRun run, boolean terminalized) {

        static RecoveryResult none() {
            return new RecoveryResult(null, false);
        }

        static RecoveryResult terminal() {
            return new RecoveryResult(null, true);
        }

        static RecoveryResult claimed(RunExecutionPort.OwnedRun run) {
            return new RecoveryResult(run, false);
        }
    }
}
