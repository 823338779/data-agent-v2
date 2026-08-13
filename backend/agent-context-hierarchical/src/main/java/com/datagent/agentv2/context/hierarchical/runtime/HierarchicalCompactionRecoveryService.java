package com.datagent.agentv2.context.hierarchical.runtime;

import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionJob;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCheckpointRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCompactionJobRepository;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCandidateGenerationService.ReadyCompaction;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction.PlannedCompaction;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import java.util.Objects;

/** 仅依据 durable job 状态和当前 checkpoint 选择生成、发布或已完成恢复动作。 */
public class HierarchicalCompactionRecoveryService {

    private static final String ATTEMPT_CONFLICT =
            "Hierarchical current-attempt compaction job conflicts with takeover";

    private final HierarchicalCompactionJobRepository jobs;
    private final HierarchicalCheckpointRepository checkpoints;
    private final HierarchicalCompactionPlanningTransaction planning;
    private final HierarchicalCandidateGenerationService generation;
    private final HierarchicalCompactionClaimTransaction claims;

    /** 便于测试及 Task 10 装配的最小构造器。 */
    /** Spring 装配可注入真实代理过的 claim 短事务。 */
    public HierarchicalCompactionRecoveryService(
            HierarchicalCompactionJobRepository jobs,
            HierarchicalCheckpointRepository checkpoints,
            HierarchicalCompactionPlanningTransaction planning,
            HierarchicalCandidateGenerationService generation,
            HierarchicalCompactionClaimTransaction claims) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.planning = Objects.requireNonNull(planning, "planning");
        this.generation = Objects.requireNonNull(generation, "generation");
        this.claims = Objects.requireNonNull(claims, "claims");
    }

    /** 已提交 publication 优先幂等观察；否则接管精确 base 边界并继续到 READY。 */
    public RecoveryResult recover(
            ContextExecutionRef execution, HierarchicalCheckpoint suppliedCheckpoint) {
        ContextExecutionRef checkedExecution = Objects.requireNonNull(execution, "execution");
        HierarchicalCheckpoint checkpoint =
                Objects.requireNonNull(suppliedCheckpoint, "suppliedCheckpoint");
        HierarchicalCheckpoint current =
                checkpoints.findCheckpoint(checkedExecution.conversationSessionId());
        if (current != null) {
            checkpoint = current;
        }

        HierarchicalCompactionJob completed =
                jobs.findCompleted(
                        checkedExecution, checkpoint.version(), checkpoint.strategyGeneration());
        if (completed != null) {
            return RecoveryResult.completed(checkpoint);
        }

        // 当前 attempt 的终态必须直接收敛，禁止落入 NONE 后在同一自然键上重新规划。
        HierarchicalCompactionJob currentAttempt = jobs.findBoundary(checkedExecution);
        if (currentAttempt != null) {
            if (currentAttempt.status() == HierarchicalCompactionJob.Status.CANCELLED) {
                return RecoveryResult.cancelled();
            }
            if (currentAttempt.status() == HierarchicalCompactionJob.Status.FAILED) {
                return RecoveryResult.failed(false);
            }
            if (currentAttempt.status() == HierarchicalCompactionJob.Status.SUCCEEDED) {
                // matching SUCCEEDED 已由 findCompleted 捕获；剩余情况是 checkpoint 未随 publication 提交。
                return RecoveryResult.failed(true);
            }
        }
        HierarchicalCompactionJob recoverable =
                jobs.findRecoverable(
                        checkedExecution, checkpoint.version(), checkpoint.strategyGeneration());
        if (recoverable == null) {
            return RecoveryResult.none();
        }

        // 当前 attempt 已有另一条自然边界 job 时，claim SQL 明确拒绝并报告稳定冲突。
        if (currentAttempt != null && !currentAttempt.jobId().equals(recoverable.jobId())) {
            throw new IllegalStateException(ATTEMPT_CONFLICT);
        }
        HierarchicalCompactionJob claimed = claims.claim(recoverable, checkedExecution);
        PlannedCompaction planned = planning.restore(claimed, checkedExecution);
        ReadyCompaction ready = generation.generate(planned);
        return ready.cancelled() ? RecoveryResult.cancelled() : RecoveryResult.publish(ready);
    }

    /** coordinator 只消费有限 durable 动作，避免根据进程内阶段猜测恢复路径。 */
    enum RecoveryAction {
        NONE,
        PUBLISH,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    /** 恢复结果只携带 body-free READY carrier 或已发布 checkpoint。 */
    record RecoveryResult(
            RecoveryAction action,
            ReadyCompaction ready,
            HierarchicalCheckpoint publishedCheckpoint,
            boolean reportFailure) {

        RecoveryResult {
            Objects.requireNonNull(action, "action");
        }

        static RecoveryResult none() {
            return new RecoveryResult(RecoveryAction.NONE, null, null, false);
        }

        static RecoveryResult publish(ReadyCompaction ready) {
            return new RecoveryResult(
                    RecoveryAction.PUBLISH, Objects.requireNonNull(ready), null, false);
        }

        static RecoveryResult completed(HierarchicalCheckpoint checkpoint) {
            return new RecoveryResult(
                    RecoveryAction.COMPLETED, null, Objects.requireNonNull(checkpoint), false);
        }

        static RecoveryResult cancelled() {
            return new RecoveryResult(RecoveryAction.CANCELLED, null, null, false);
        }

        static RecoveryResult failed(boolean reportFailure) {
            return new RecoveryResult(RecoveryAction.FAILED, null, null, reportFailure);
        }
    }
}
