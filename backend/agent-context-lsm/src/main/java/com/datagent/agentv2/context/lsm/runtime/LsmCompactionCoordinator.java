package com.datagent.agentv2.context.lsm.runtime;

import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan;
import com.datagent.agentv2.context.lsm.domain.LsmContextManifest;
import com.datagent.agentv2.context.lsm.domain.LsmPlanningInput;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator.GenerationRequest;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextPolicySnapshot;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 以规划短事务、事务外候选生成和发布短事务协调一次可恢复压缩。 */
public final class LsmCompactionCoordinator {

    private static final int MAX_REPLANS = 2;

    private final LsmCompactionPlanningTransaction planning;
    private final LsmCompactionRecoveryService recovery;
    private final LsmManifestPublicationTransaction publication;

    public LsmCompactionCoordinator(
            LsmCompactionPlanningTransaction planning,
            LsmCompactionRecoveryService recovery,
            LsmManifestPublicationTransaction publication) {
        this.planning = Objects.requireNonNull(planning, "planning");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.publication = Objects.requireNonNull(publication, "publication");
    }

    /** 不建立外层事务；CAS 冲突后最多再执行两轮规划。 */
    public CompactionOutcome compactIfRequired(CompactionRequest request) {
        Objects.requireNonNull(request, "request");
        CompactionRequest currentRequest = request;
        for (int replan = 0; replan <= MAX_REPLANS; replan++) {
            PlannedCompaction planned = planning.begin(currentRequest);
            if (planned.noop()) {
                return CompactionOutcome.unchanged(currentManifest(currentRequest));
            }
            if (planned.status() == JobStatus.PUBLISHED) {
                return new CompactionOutcome(
                        PublishResult.PUBLISHED, planned.plan().proposedManifest());
            }
            if (planned.status() == JobStatus.SUPERSEDED) {
                if (replan == MAX_REPLANS) {
                    return new CompactionOutcome(
                            PublishResult.SUPERSEDED, currentManifest(currentRequest));
                }
                currentRequest =
                        Objects.requireNonNull(
                                currentRequest.replanProvider().refresh(currentRequest),
                                "replanned request");
                continue;
            }
            if (planned.status() == JobStatus.CANCELLED) {
                return new CompactionOutcome(
                        PublishResult.CANCELLED, currentManifest(currentRequest));
            }
            ReadyCompaction ready = recovery.resume(planned);
            if (ready.cancelled()) {
                return new CompactionOutcome(
                        PublishResult.CANCELLED, currentManifest(currentRequest));
            }
            PublishResult result = publication.publish(ready);
            if (result != PublishResult.SUPERSEDED || replan == MAX_REPLANS) {
                LsmContextManifest manifest =
                        result == PublishResult.PUBLISHED
                                ? ready.plan().proposedManifest()
                                : currentManifest(currentRequest);
                return new CompactionOutcome(result, manifest);
            }
            currentRequest =
                    Objects.requireNonNull(
                            currentRequest.replanProvider().refresh(currentRequest),
                            "replanned request");
        }
        throw new IllegalStateException("unreachable compaction outcome");
    }

    private static LsmContextManifest currentManifest(CompactionRequest request) {
        return request.planningInput() == null
                ? LsmContextManifest.empty()
                : request.planningInput().manifest();
    }

    /** 一次规划所需的显式不可变输入；候选正文不会被持久化。 */
    public record CompactionRequest(
            /** 当前 execution 的 owner、attempt 与输入 fencing 边界。 */
            ContextExecutionRef execution,
            /** execution 首次进入 LSM 时冻结的策略快照。 */
            ContextPolicySnapshot policySnapshot,
            /** planner 的完整不可变领域输入。 */
            LsmPlanningInput planningInput,
            /** 每个生成目标对应的可信摘要输入。 */
            List<CandidateInput> candidateInputs,
            /** CAS 冲突后加载最新 manifest、来源和内存正文的回调。 */
            ReplanProvider replanProvider) {

        public CompactionRequest {
            execution = Objects.requireNonNull(execution, "execution");
            policySnapshot = Objects.requireNonNull(policySnapshot, "policySnapshot");
            planningInput = Objects.requireNonNull(planningInput, "planningInput");
            candidateInputs =
                    List.copyOf(Objects.requireNonNull(candidateInputs, "candidateInputs"));
            replanProvider = Objects.requireNonNull(replanProvider, "replanProvider");
            Set<String> targetIds = new HashSet<>();
            for (CandidateInput candidate : candidateInputs) {
                if (!targetIds.add(candidate.targetId())) {
                    throw new IllegalArgumentException("candidate targetId must be unique");
                }
            }
            LsmCompactionPlan constructorPlan =
                    new com.datagent.agentv2.context.lsm.planning.LsmCompactionPlanner()
                            .plan(planningInput);
            Set<String> plannedTargets =
                    constructorPlan.operations().stream()
                            .filter(operation -> operation.targetId() != null)
                            .map(LsmCompactionPlan.PlannedOperation::targetId)
                            .collect(java.util.stream.Collectors.toSet());
            if (!plannedTargets.equals(targetIds)) {
                throw new IllegalArgumentException(
                        "candidate inputs must match plan targets exactly");
            }
            java.util.Map<String, LsmCompactionPlan.PlannedOperation> operationsByTarget =
                    constructorPlan.operations().stream()
                            .filter(operation -> operation.targetId() != null)
                            .collect(
                                    java.util.stream.Collectors.toMap(
                                            LsmCompactionPlan.PlannedOperation::targetId,
                                            operation -> operation));
            for (CandidateInput candidate : candidateInputs) {
                LsmCompactionPlan.PlannedOperation operation =
                        operationsByTarget.get(candidate.targetId());
                GenerationRequest generation = candidate.generationRequest();
                if (generation.kind() != operation.targetKind()
                        || generation.level() != operation.targetLevel()
                        || !generation.turnCoverage().equals(operation.coverage())
                        || (!operation.sourceIds().isEmpty()
                                && !generation.sourceIds().equals(operation.sourceIds()))) {
                    throw new IllegalArgumentException(
                            "candidate metadata must match its plan target");
                }
            }
        }
    }

    /** 由 Task 9 assembler 实现，runtime 不自行读取 Web/history 正文。 */
    @FunctionalInterface
    public interface ReplanProvider {

        CompactionRequest refresh(CompactionRequest superseded);
    }

    /** Task 7 生成请求与 planner target 的稳定一一映射。 */
    public record CandidateInput(
            /** planner operation 的唯一目标标识。 */
            String targetId,
            /** 含权威 turn/message coverage、来源类型、来源 ID 与内存正文的生成请求。 */
            GenerationRequest generationRequest,
            /** 高层候选按 sourceIds 顺序引用的直接子摘要；本计划子节点的正文在生成后解析。 */
            List<SummarySource> summarySources) {

        public CandidateInput {
            targetId = Objects.requireNonNull(targetId, "targetId");
            generationRequest = Objects.requireNonNull(generationRequest, "generationRequest");
            summarySources = List.copyOf(Objects.requireNonNull(summarySources, "summarySources"));
            if (targetId.isBlank()) {
                throw new IllegalArgumentException("targetId must not be blank");
            }
            if (!summarySources.isEmpty()
                    && !generationRequest
                            .sourceIds()
                            .equals(
                                    summarySources.stream()
                                            .map(SummarySource::sourceId)
                                            .toList())) {
                throw new IllegalArgumentException(
                        "summary sources must match generation source IDs exactly");
            }
        }

        public CandidateInput(String targetId, GenerationRequest generationRequest) {
            this(targetId, generationRequest, List.of());
        }
    }

    /** 一个高层候选的直接子摘要；planned source 在 recovery 顺序生成后补入正文。 */
    public record SummarySource(String sourceId, String summaryDocument) {

        public SummarySource {
            sourceId = Objects.requireNonNull(sourceId, "sourceId");
        }

        public boolean planned() {
            return summaryDocument == null;
        }
    }

    /** 可由事务外生成阶段继续执行的持久化规划快照。 */
    public record PlannedCompaction(
            /** 压缩作业业务标识。 */
            String jobId,
            /** 当前调用提供的内存输入。 */
            CompactionRequest request,
            /** 不含正文的确定性 planner 结果。 */
            LsmCompactionPlan plan,
            /** 规划读取的 manifest generation。 */
            long sourceManifestGeneration,
            /** 规划读取的 manifest version。 */
            long sourceManifestVersion,
            /** 当前作业状态。 */
            JobStatus status,
            /** 是否不需要压缩。 */
            boolean noop) {

        public PlannedCompaction {
            request = Objects.requireNonNull(request, "request");
            status = Objects.requireNonNull(status, "status");
            if (!noop && status != JobStatus.SUPERSEDED) {
                jobId = Objects.requireNonNull(jobId, "jobId");
                plan = Objects.requireNonNull(plan, "plan");
            }
        }

        public static PlannedCompaction noop(CompactionRequest request) {
            return new PlannedCompaction(null, request, null, 0L, 0L, JobStatus.PLANNED, true);
        }

        public static PlannedCompaction cancelled(
                String jobId,
                CompactionRequest request,
                LsmCompactionPlan plan,
                long sourceManifestGeneration,
                long sourceManifestVersion) {
            return new PlannedCompaction(
                    jobId,
                    request,
                    plan,
                    sourceManifestGeneration,
                    sourceManifestVersion,
                    JobStatus.CANCELLED,
                    false);
        }

        public static PlannedCompaction superseded(
                CompactionRequest request, LsmCompactionPlan plan) {
            return new PlannedCompaction(
                    null,
                    request,
                    plan,
                    request.planningInput().manifest().generation(),
                    0L,
                    JobStatus.SUPERSEDED,
                    false);
        }
    }

    /** 已生成且经过领域不变量检查、等待原子发布的候选集合。 */
    public record ReadyCompaction(
            /** 来源规划和 fencing 快照。 */
            PlannedCompaction planned,
            /** 目标 ID 已替换为真实不可变 segment ID 的发布计划。 */
            LsmCompactionPlan plan,
            /** 真实候选段，按 planner operation 顺序排列。 */
            List<LsmSummarySegment> candidates,
            /** 生成期间是否观察到取消。 */
            boolean cancelled) {

        public ReadyCompaction {
            planned = Objects.requireNonNull(planned, "planned");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
            if (!cancelled) {
                plan = Objects.requireNonNull(plan, "plan");
            }
        }
    }

    /** 一次协调的可观察结果及其仍然有效的 manifest。 */
    public record CompactionOutcome(
            /** 发布或终止结果。 */
            PublishResult publishResult,
            /** 结果对应的有效 manifest。 */
            LsmContextManifest manifest) {

        public CompactionOutcome {
            publishResult = Objects.requireNonNull(publishResult, "publishResult");
            manifest = Objects.requireNonNull(manifest, "manifest");
        }

        public static CompactionOutcome unchanged(LsmContextManifest manifest) {
            return new CompactionOutcome(PublishResult.UNCHANGED, manifest);
        }

        public static CompactionOutcome unchanged() {
            return unchanged(LsmContextManifest.empty());
        }
    }

    /** 发布阶段的有限结果集合。 */
    public enum PublishResult {
        UNCHANGED,
        PUBLISHED,
        SUPERSEDED,
        CANCELLED
    }

    /** 持久化 job 的完整状态机值。 */
    public enum JobStatus {
        PLANNED,
        GENERATING,
        READY,
        PUBLISHED,
        SUPERSEDED,
        CANCELLED,
        FAILED
    }
}
