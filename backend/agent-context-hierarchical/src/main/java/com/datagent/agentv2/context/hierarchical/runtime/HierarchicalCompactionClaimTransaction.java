package com.datagent.agentv2.context.hierarchical.runtime;

import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionJob;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCompactionJobRepository;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import java.util.Objects;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 在生成事务外先提交 job owner/attempt 接管，避免 NOT_SUPPORTED 看不到未提交 fence。 */
public class HierarchicalCompactionClaimTransaction {

    private static final String CLAIM_LOST = "Hierarchical compaction takeover was lost";

    private final HierarchicalCompactionJobRepository jobs;
    private final ContextExecutionControlPort executions;

    public HierarchicalCompactionClaimTransaction(
            HierarchicalCompactionJobRepository jobs, ContextExecutionControlPort executions) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.executions = Objects.requireNonNull(executions, "executions");
    }

    /** execution 行锁与 job CAS 同处独立短事务，返回前 owner/attempt 已对生成连接可见。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public HierarchicalCompactionJob claim(
            HierarchicalCompactionJob recoverable, ContextExecutionRef execution) {
        executions.assertOwnedAndWithinBudget(execution);
        if (recoverable.executionAttempt() == execution.executionAttempt()
                && recoverable.ownerInstanceId().equals(execution.ownerInstanceId())) {
            HierarchicalCompactionJob current = jobs.findJob(recoverable.jobId());
            if (!sameClaimState(recoverable, current, execution)) {
                throw new IllegalStateException(CLAIM_LOST);
            }
            return current;
        }
        if (!jobs.claimRecoverable(recoverable, execution)) {
            throw new IllegalStateException(CLAIM_LOST);
        }
        HierarchicalCompactionJob claimed = jobs.findJob(recoverable.jobId());
        if (claimed == null
                || claimed.executionAttempt() != execution.executionAttempt()
                || !claimed.ownerInstanceId().equals(execution.ownerInstanceId())) {
            throw new IllegalStateException(CLAIM_LOST);
        }
        return claimed;
    }

    private static boolean sameClaimState(
            HierarchicalCompactionJob expected,
            HierarchicalCompactionJob current,
            ContextExecutionRef execution) {
        return current != null
                && current.status() == expected.status()
                && current.version() == expected.version()
                && current.baseCheckpointVersion() == expected.baseCheckpointVersion()
                && current.baseStrategyGeneration() == expected.baseStrategyGeneration()
                && current.executionAttempt() == execution.executionAttempt()
                && current.ownerInstanceId().equals(execution.ownerInstanceId())
                && current.inputUpToMessageSequence() == execution.inputUpToMessageSequence();
    }
}
