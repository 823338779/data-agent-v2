package com.datagent.agentv2.context.hierarchical.runtime;

import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionJob;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionPlan;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalSummaryNode;
import com.datagent.agentv2.context.hierarchical.domain.SummaryRange;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCompactionJobRepository;
import com.datagent.agentv2.context.hierarchical.planning.BinarySummaryTreePlanner;
import com.datagent.agentv2.context.hierarchical.planning.BinarySummaryTreePlanner.PlanningInput;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GenerationRange;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.SourceKind;
import com.datagent.agentv2.context.hierarchical.summary.SummaryGenerationIdentity;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort.ContextLifecycleEvent;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 在独立短事务内冻结压缩边界并持久化不含正文的可恢复计划。 */
public class HierarchicalCompactionPlanningTransaction {

    private static final int PLAN_SCHEMA_VERSION = 1;
    private static final String JOB_ID_PREFIX = "hierarchical_compaction_job";
    private static final String EVENT_JOB_ID = "compactionJobId";
    private static final String EVENT_INPUT_BOUNDARY = "inputUpToMessageSequence";
    private static final String INVALID_PLAN_DOCUMENT = "Invalid hierarchical plan document";
    private static final String INCOMPATIBLE_PERSISTED_JOB =
            "Persisted hierarchical job is incompatible with the caller boundary";

    private final BinarySummaryTreePlanner planner;
    private final HierarchicalSummaryGenerator generator;
    private final HierarchicalCompactionJobRepository jobs;
    private final IdGenerator ids;
    private final ContextExecutionControlPort executions;
    private final ObjectMapper json;

    /** 固定纯规划器、确定性 key、job 仓储、ID 和 execution control 依赖。 */
    public HierarchicalCompactionPlanningTransaction(
            BinarySummaryTreePlanner planner,
            HierarchicalSummaryGenerator generator,
            HierarchicalCompactionJobRepository jobs,
            IdGenerator ids,
            ContextExecutionControlPort executions,
            ObjectMapper json) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.generator = Objects.requireNonNull(generator, "generator");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.json = Objects.requireNonNull(json, "json");
    }

    /** execution 门禁、固定计划写入和 STARTED 发布都位于同一 REQUIRES_NEW 短事务。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PlannedCompaction plan(CompactionRequest request, PlanningInput planningInput) {
        CompactionRequest checked = Objects.requireNonNull(request, "request");
        PlanningInput input = Objects.requireNonNull(planningInput, "planningInput");
        executions.assertOwnedAndWithinBudget(checked.execution());
        HierarchicalCompactionPlan transientPlan = planner.plan(input);
        SummaryGenerationIdentity identity =
                generator.generationIdentity(
                        checked.execution().conversationSessionId(), checked.policySnapshot());
        GenerationSettings transientSettings =
                GenerationSettings.from(
                        checked.policySnapshot(), checked.maxSummaryTokens(), identity);
        List<PlannedCandidate> transientCandidates =
                plannedCandidates(checked, input, transientPlan, identity);
        PlannedCandidate transientCold =
                plannedColdCandidate(checked, input, transientPlan, identity);
        HierarchicalCheckpoint checkpoint = input.checkpoint();
        DurablePlanDocument transientDocument =
                new DurablePlanDocument(
                        PLAN_SCHEMA_VERSION,
                        checkpoint.version(),
                        checkpoint.strategyGeneration(),
                        checked.execution().inputUpToMessageSequence(),
                        checked.policySnapshot().targetInputTokens(input.budget()),
                        transientSettings,
                        transientPlan,
                        transientCandidates,
                        transientCold);

        // job 只冻结 fencing、范围、ID 和预算；历史消息及摘要正文继续由 source provider 持有。
        HierarchicalCompactionJob candidate =
                new HierarchicalCompactionJob(
                        ids.next(JOB_ID_PREFIX),
                        checked.execution().conversationSessionId(),
                        checked.execution().executionId(),
                        checked.execution().executionAttempt(),
                        checked.execution().ownerInstanceId(),
                        checked.execution().inputUpToMessageSequence(),
                        checkpoint.version(),
                        checkpoint.strategyGeneration(),
                        writePlan(transientDocument),
                        HierarchicalCompactionJob.Status.PLANNED,
                        null,
                        0L);
        HierarchicalCompactionJob persisted = jobs.insertOrSelect(candidate);
        requireCompatible(persisted, checked.execution());

        // 唯一边界冲突后 persisted plan 是唯一事实源，禁止继续使用刚重算的瞬态计划。
        DurablePlanDocument durable = readPlan(persisted.planDocument());
        validateDurablePlan(persisted, durable);

        // 只有本次首次插入的 job 发布 STARTED；唯一边界竞争返回既有 job 时不会重复事件。
        if (candidate.jobId().equals(persisted.jobId())) {
            executions.publish(
                    checked.execution(),
                    new ContextLifecycleEvent(
                            ContextLifecycleEvent.Type.STARTED,
                            Map.of(
                                    EVENT_JOB_ID,
                                    persisted.jobId(),
                                    EVENT_INPUT_BOUNDARY,
                                    persisted.inputUpToMessageSequence())));
        }
        return new PlannedCompaction(
                persisted,
                checked.execution(),
                durable.generationSettings(),
                durable.plan(),
                durable.candidates(),
                durable.coldCandidate());
    }

    private List<PlannedCandidate> plannedCandidates(
            CompactionRequest request,
            PlanningInput input,
            HierarchicalCompactionPlan plan,
            SummaryGenerationIdentity identity) {
        Map<RangeKey, PlannedCandidate> ordered = new LinkedHashMap<>();
        List<SummaryRange> targets = new ArrayList<>();
        targets.addAll(plan.coldPromotionRanges());
        targets.addAll(plan.frontier().ranges());
        targets.addAll(plan.frontier().missingRanges());
        targets.addAll(plan.hotDegradationRanges());
        for (SummaryRange target : targets) {
            addBottomUp(request, input, target, identity, ordered);
        }
        return List.copyOf(ordered.values());
    }

    private PlannedCandidate addBottomUp(
            CompactionRequest request,
            PlanningInput input,
            SummaryRange range,
            SummaryGenerationIdentity identity,
            Map<RangeKey, PlannedCandidate> ordered) {
        RangeKey key = RangeKey.from(range);
        PlannedCandidate known = ordered.get(key);
        if (known != null) {
            return known;
        }
        GenerationRange generationRange = generationRange(range);
        HierarchicalSummaryNode existing = existingNode(input.availableNodes(), range);
        if (existing != null) {
            PlannedCandidate reused =
                    descriptor(
                            existing.nodeId(),
                            generationRange,
                            range.level() == 0 ? SourceKind.LEAF : SourceKind.PARENT,
                            existing.leftChildNodeId(),
                            existing.rightChildNodeId(),
                            identity);
            ordered.put(key, reused);
            return reused;
        }

        PlannedCandidate left = null;
        PlannedCandidate right = null;
        if (range.level() > 0) {
            left = addBottomUp(request, input, childRange(input, range, false), identity, ordered);
            right = addBottomUp(request, input, childRange(input, range, true), identity, ordered);
        }
        SourceKind kind = range.level() == 0 ? SourceKind.LEAF : SourceKind.PARENT;
        String nodeId =
                generator.nodeKey(
                        request.execution().conversationSessionId(),
                        generationRange,
                        kind,
                        request.policySnapshot());
        PlannedCandidate planned =
                descriptor(
                        nodeId,
                        generationRange,
                        kind,
                        left == null ? null : left.nodeId(),
                        right == null ? null : right.nodeId(),
                        identity);
        ordered.put(key, planned);
        return planned;
    }

    private PlannedCandidate plannedColdCandidate(
            CompactionRequest request,
            PlanningInput input,
            HierarchicalCompactionPlan plan,
            SummaryGenerationIdentity identity) {
        if (plan.coldPromotionRanges().isEmpty()) {
            return null;
        }
        long endTurn = plan.coldTarget().endTurn();
        GenerationRange range =
                new GenerationRange(
                        1L,
                        endTurn,
                        input.turns().getFirst().startMessageSequence(),
                        input.turns().get(Math.toIntExact(endTurn - 1L)).endMessageSequence(),
                        0);
        String nodeId =
                generator.nodeKey(
                        request.execution().conversationSessionId(),
                        range,
                        SourceKind.COLD,
                        request.policySnapshot());
        return descriptor(nodeId, range, SourceKind.COLD, null, null, identity);
    }

    private static PlannedCandidate descriptor(
            String nodeId,
            GenerationRange range,
            SourceKind sourceKind,
            String leftChildNodeId,
            String rightChildNodeId,
            SummaryGenerationIdentity identity) {
        return new PlannedCandidate(
                nodeId,
                range,
                sourceKind,
                leftChildNodeId,
                rightChildNodeId,
                identity.schemaVersion(),
                identity.promptVersion(),
                identity.modelIdentity().provider(),
                identity.modelIdentity().model());
    }

    private String writePlan(DurablePlanDocument document) {
        try {
            return json.writeValueAsString(document);
        } catch (RuntimeException failure) {
            throw new IllegalStateException(INVALID_PLAN_DOCUMENT, failure);
        }
    }

    private DurablePlanDocument readPlan(String document) {
        try {
            DurablePlanDocument durable = json.readValue(document, DurablePlanDocument.class);
            if (durable.schemaVersion() != PLAN_SCHEMA_VERSION) {
                throw new IllegalArgumentException(INVALID_PLAN_DOCUMENT);
            }
            return durable;
        } catch (RuntimeException failure) {
            throw new IllegalStateException(INVALID_PLAN_DOCUMENT, failure);
        }
    }

    private static void requireCompatible(
            HierarchicalCompactionJob job, ContextExecutionRef execution) {
        if (!job.conversationSessionId().equals(execution.conversationSessionId())
                || !job.executionId().equals(execution.executionId())
                || job.executionAttempt() != execution.executionAttempt()
                || !job.ownerInstanceId().equals(execution.ownerInstanceId())
                || job.inputUpToMessageSequence() != execution.inputUpToMessageSequence()) {
            throw new IllegalStateException(INCOMPATIBLE_PERSISTED_JOB);
        }
    }

    private static void validateDurablePlan(
            HierarchicalCompactionJob job, DurablePlanDocument document) {
        if (document.baseCheckpointVersion() != job.baseCheckpointVersion()
                || document.baseStrategyGeneration() != job.baseStrategyGeneration()
                || document.inputUpToMessageSequence() != job.inputUpToMessageSequence()
                || document.targetInputTokens() < 0) {
            throw new IllegalStateException(INVALID_PLAN_DOCUMENT);
        }
        validatePlanReferences(document);
    }

    private static void validatePlanReferences(DurablePlanDocument document) {
        GenerationSettings settings = document.generationSettings();
        for (PlannedCandidate candidate : document.candidates()) {
            if (!settings.matches(candidate) || candidate.sourceKind() == SourceKind.COLD) {
                throw new IllegalStateException(INVALID_PLAN_DOCUMENT);
            }
        }
        boolean coldRequired = !document.plan().coldPromotionRanges().isEmpty();
        if (coldRequired != (document.coldCandidate() != null)
                || document.coldCandidate() != null
                        && (!settings.matches(document.coldCandidate())
                                || document.coldCandidate().sourceKind() != SourceKind.COLD)) {
            throw new IllegalStateException(INVALID_PLAN_DOCUMENT);
        }
        for (int index = 0; index < document.plan().frontier().nodeIds().size(); index++) {
            String nodeId = document.plan().frontier().nodeIds().get(index);
            SummaryRange range = document.plan().frontier().ranges().get(index);
            if (!containsDescriptor(document.candidates(), nodeId, range)) {
                throw new IllegalStateException(INVALID_PLAN_DOCUMENT);
            }
        }
        List<SummaryRange> referencedRanges = new ArrayList<>();
        referencedRanges.addAll(document.plan().coldPromotionRanges());
        referencedRanges.addAll(document.plan().frontier().missingRanges());
        referencedRanges.addAll(document.plan().hotDegradationRanges());
        for (SummaryRange range : referencedRanges) {
            if (!containsDescriptor(document.candidates(), null, range)) {
                throw new IllegalStateException(INVALID_PLAN_DOCUMENT);
            }
        }
    }

    private static boolean containsDescriptor(
            List<PlannedCandidate> candidates, String nodeId, SummaryRange range) {
        GenerationRange expectedRange = generationRange(range);
        return candidates.stream()
                .anyMatch(
                        candidate ->
                                (nodeId == null || nodeId.equals(candidate.nodeId()))
                                        && expectedRange.equals(candidate.range()));
    }

    private static HierarchicalSummaryNode existingNode(
            List<HierarchicalSummaryNode> availableNodes, SummaryRange range) {
        return availableNodes.stream()
                .filter(
                        node ->
                                node.level() == range.level()
                                        && node.startConversationMessageSequence()
                                                == range.startMessageSequence()
                                        && node.endConversationMessageSequence()
                                                == range.endMessageSequence())
                .findFirst()
                .orElse(null);
    }

    private static SummaryRange childRange(
            PlanningInput input, SummaryRange parent, boolean right) {
        long childTurns = 1L << (parent.level() - 1);
        long startTurn = right ? parent.startTurn() + childTurns : parent.startTurn();
        long endTurn = startTurn + childTurns - 1L;
        long startMessage =
                input.turns().get(Math.toIntExact(startTurn - 1L)).startMessageSequence();
        long endMessage = input.turns().get(Math.toIntExact(endTurn - 1L)).endMessageSequence();
        return new SummaryRange(startTurn, endTurn, startMessage, endMessage, parent.level() - 1);
    }

    private static GenerationRange generationRange(SummaryRange range) {
        return new GenerationRange(
                range.startTurn(),
                range.endTurn(),
                range.startMessageSequence(),
                range.endMessageSequence(),
                range.level());
    }

    /** 规划 carrier 仅包含 execution fencing、冻结策略和生成预算，不持有任何正文。 */
    record CompactionRequest(
            ContextExecutionRef execution,
            HierarchicalContextPolicySnapshot policySnapshot,
            int maxSummaryTokens) {

        CompactionRequest {
            execution = Objects.requireNonNull(execution, "execution");
            policySnapshot = Objects.requireNonNull(policySnapshot, "policySnapshot");
            if (maxSummaryTokens <= 0) {
                throw new IllegalArgumentException("maxSummaryTokens must be positive");
            }
        }
    }

    /** 生成阶段 carrier 仅保留 durable job、execution fence、冻结设置和范围元数据。 */
    record PlannedCompaction(
            HierarchicalCompactionJob job,
            ContextExecutionRef execution,
            GenerationSettings generationSettings,
            HierarchicalCompactionPlan plan,
            List<PlannedCandidate> candidates,
            PlannedCandidate coldCandidate) {

        PlannedCompaction {
            Objects.requireNonNull(job, "job");
            Objects.requireNonNull(execution, "execution");
            Objects.requireNonNull(generationSettings, "generationSettings");
            Objects.requireNonNull(plan, "plan");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        }
    }

    /** durable plan 冻结模型调用使用的完整策略、token 上限和生成身份。 */
    record GenerationSettings(
            HierarchicalContextPolicySnapshot policySnapshot,
            int maxSummaryTokens,
            int schemaVersion,
            int promptVersion,
            String modelProvider,
            String modelName) {

        GenerationSettings {
            policySnapshot = Objects.requireNonNull(policySnapshot, "policySnapshot");
            Objects.requireNonNull(modelProvider, "modelProvider");
            Objects.requireNonNull(modelName, "modelName");
            if (maxSummaryTokens <= 0
                    || schemaVersion != policySnapshot.summarySchemaVersion()
                    || promptVersion != policySnapshot.summaryPromptVersion()
                    || modelProvider.isBlank()
                    || modelName.isBlank()) {
                throw new IllegalArgumentException("Invalid hierarchical generation settings");
            }
        }

        private static GenerationSettings from(
                HierarchicalContextPolicySnapshot policySnapshot,
                int maxSummaryTokens,
                SummaryGenerationIdentity identity) {
            return new GenerationSettings(
                    policySnapshot,
                    maxSummaryTokens,
                    identity.schemaVersion(),
                    identity.promptVersion(),
                    identity.modelIdentity().provider(),
                    identity.modelIdentity().model());
        }

        boolean matches(PlannedCandidate candidate) {
            return schemaVersion == candidate.schemaVersion()
                    && promptVersion == candidate.promptVersion()
                    && modelProvider.equals(candidate.modelProvider())
                    && modelName.equals(candidate.modelName());
        }
    }

    /** 单个候选只描述确定性 ID、可信范围、树关系和冻结生成身份。 */
    record PlannedCandidate(
            String nodeId,
            GenerationRange range,
            SourceKind sourceKind,
            String leftChildNodeId,
            String rightChildNodeId,
            int schemaVersion,
            int promptVersion,
            String modelProvider,
            String modelName) {

        PlannedCandidate {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(range, "range");
            Objects.requireNonNull(sourceKind, "sourceKind");
            Objects.requireNonNull(modelProvider, "modelProvider");
            Objects.requireNonNull(modelName, "modelName");
            boolean leaf = sourceKind == SourceKind.LEAF;
            boolean parent = sourceKind == SourceKind.PARENT;
            boolean childrenValid =
                    parent
                            ? leftChildNodeId != null && rightChildNodeId != null
                            : leftChildNodeId == null && rightChildNodeId == null;
            if (nodeId.isBlank()
                    || schemaVersion <= 0
                    || promptVersion <= 0
                    || modelProvider.isBlank()
                    || modelName.isBlank()
                    || (!leaf && !parent && sourceKind != SourceKind.COLD)
                    || !childrenValid) {
                throw new IllegalArgumentException("Invalid planned hierarchical candidate");
            }
        }
    }

    /** body-free JSON 文档冻结发布所需的版本、预算、层级规划和候选身份。 */
    private record DurablePlanDocument(
            int schemaVersion,
            long baseCheckpointVersion,
            long baseStrategyGeneration,
            long inputUpToMessageSequence,
            int targetInputTokens,
            GenerationSettings generationSettings,
            HierarchicalCompactionPlan plan,
            List<PlannedCandidate> candidates,
            PlannedCandidate coldCandidate) {

        private DurablePlanDocument {
            Objects.requireNonNull(generationSettings, "generationSettings");
            Objects.requireNonNull(plan, "plan");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        }
    }

    private record RangeKey(long startTurn, long endTurn, int level) {

        private static RangeKey from(SummaryRange range) {
            return new RangeKey(range.startTurn(), range.endTurn(), range.level());
        }
    }
}
