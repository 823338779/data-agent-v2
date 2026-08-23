package com.datagent.agentv2.context.lsm.runtime;

import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan;
import com.datagent.agentv2.context.lsm.domain.LsmContextManifest;
import com.datagent.agentv2.context.lsm.domain.LsmContextPolicySnapshot;
import com.datagent.agentv2.context.lsm.domain.LsmPlanningInput;
import com.datagent.agentv2.context.lsm.observability.LsmContextMetrics;
import com.datagent.agentv2.context.lsm.observability.LsmContextMetrics.CompactionResult;
import com.datagent.agentv2.context.lsm.planning.LsmCompactionPlanner;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator.GenerationRequest;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 在当前 execution 内同步完成规划、摘要生成和 manifest CAS 发布。
 *
 * <p>摘要生成可能耗时，因此生成前后都检查执行是否仍有效。发布冲突只允许刷新一次输入重新规划，避免在持续写入的会话上形成无界重试。
 */
public final class LsmCompactionCoordinator {

    private static final int MAX_REPLANS = 1;

    private final LsmCompactionPlanner planner;
    private final LsmCandidateGenerator generator;
    private final LsmManifestPublicationTransaction publication;
    private final ContextExecutionControlPort executions;
    private final LsmContextMetrics metrics;

    public LsmCompactionCoordinator(
            LsmCompactionPlanner planner,
            LsmCandidateGenerator generator,
            LsmManifestPublicationTransaction publication,
            ContextExecutionControlPort executions,
            LsmContextMetrics metrics) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.generator = Objects.requireNonNull(generator, "generator");
        this.publication = Objects.requireNonNull(publication, "publication");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public CompactionOutcome compactIfRequired(CompactionRequest request) {
        Objects.requireNonNull(request, "request");
        Instant startedAt = Instant.now();
        CompactionRequest current = request;
        LsmCompactionPlan lastPlan = null;
        try {
            for (int replan = 0; replan <= MAX_REPLANS; replan++) {
                executions.assertRunningAndWithinDeadline(current.execution());
                LsmCompactionPlan plan = planner.plan(current.planningInput());
                lastPlan = plan;
                if (plan.operations().isEmpty()) {
                    return observed(
                            CompactionOutcome.unchanged(plan.proposedManifest()), plan, startedAt);
                }
                LsmCandidateGenerator.GeneratedCompaction generated =
                        generator.generate(current, plan);
                executions.assertRunningAndWithinDeadline(current.execution());
                PublishResult result = publication.publish(current, generated);
                if (result == PublishResult.PUBLISHED) {
                    return observed(
                            new CompactionOutcome(result, generated.plan().proposedManifest()),
                            plan,
                            startedAt);
                }
                if (replan == MAX_REPLANS) {
                    // CAS 冲突已达到上限时返回当前 manifest，调用方可继续使用未压缩上下文完成本次请求。
                    return observed(
                            new CompactionOutcome(result, current.planningInput().manifest()),
                            plan,
                            startedAt);
                }
                current =
                        Objects.requireNonNull(
                                current.replanProvider().refresh(current), "replanned request");
            }
        } catch (RuntimeException failure) {
            record(lastPlan, CompactionResult.FAILED, startedAt);
            throw failure;
        }
        throw new IllegalStateException("unreachable compaction outcome");
    }

    private CompactionOutcome observed(
            CompactionOutcome outcome, LsmCompactionPlan plan, Instant startedAt) {
        record(plan, CompactionResult.valueOf(outcome.publishResult().name()), startedAt);
        return outcome;
    }

    private void record(LsmCompactionPlan plan, CompactionResult result, Instant startedAt) {
        if (plan != null) {
            Duration duration = Duration.between(startedAt, Instant.now());
            plan.operations().stream()
                    .map(LsmCompactionPlan.PlannedOperation::type)
                    .distinct()
                    .forEach(type -> metrics.recordCompaction(result, type, duration));
        }
    }

    public record CompactionRequest(
            /** 当前对话执行快照，用于压缩前后校验取消和截止时间。 */
            ContextExecutionRef execution,
            /** 本次规划固定使用的策略快照，防止生成过程中配置变化。 */
            LsmContextPolicySnapshot policySnapshot,
            /** manifest、活动段和原始轮次组成的不可变规划输入。 */
            LsmPlanningInput planningInput,
            /** 按目标段标识索引的摘要生成输入。 */
            List<CandidateInput> candidateInputs,
            /** manifest CAS 冲突后刷新规划输入的回调。 */
            ReplanProvider replanProvider) {

        public CompactionRequest {
            Objects.requireNonNull(execution, "execution");
            Objects.requireNonNull(policySnapshot, "policySnapshot");
            Objects.requireNonNull(planningInput, "planningInput");
            candidateInputs = List.copyOf(candidateInputs);
            Objects.requireNonNull(replanProvider, "replanProvider");
            Set<String> targetIds = new HashSet<>();
            candidateInputs.forEach(
                    candidate -> {
                        if (!targetIds.add(candidate.targetId())) {
                            throw new IllegalArgumentException("candidate targetId must be unique");
                        }
                    });
        }
    }

    @FunctionalInterface
    public interface ReplanProvider {
        CompactionRequest refresh(CompactionRequest superseded);
    }

    public record CandidateInput(
            String targetId,
            GenerationRequest generationRequest,
            List<SummarySource> summarySources) {
        public CandidateInput {
            Objects.requireNonNull(targetId, "targetId");
            Objects.requireNonNull(generationRequest, "generationRequest");
            summarySources = List.copyOf(summarySources);
        }

        public CandidateInput(String targetId, GenerationRequest generationRequest) {
            this(targetId, generationRequest, List.of());
        }
    }

    public record SummarySource(String sourceId, String summaryDocument) {
        public boolean planned() {
            return summaryDocument == null;
        }
    }

    public record CompactionOutcome(PublishResult publishResult, LsmContextManifest manifest) {
        public static CompactionOutcome unchanged(LsmContextManifest manifest) {
            return new CompactionOutcome(PublishResult.UNCHANGED, manifest);
        }
    }

    public enum PublishResult {
        UNCHANGED,
        PUBLISHED,
        SUPERSEDED
    }
}
