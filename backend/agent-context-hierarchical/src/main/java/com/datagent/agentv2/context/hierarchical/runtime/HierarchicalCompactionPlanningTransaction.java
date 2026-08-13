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
public final class HierarchicalCompactionPlanningTransaction {

    private static final int PLAN_SCHEMA_VERSION = 1;
    private static final String JOB_ID_PREFIX = "hierarchical_compaction_job";
    private static final String EVENT_JOB_ID = "compactionJobId";
    private static final String EVENT_INPUT_BOUNDARY = "inputUpToMessageSequence";
    private static final String INVALID_PLAN_DOCUMENT = "Invalid hierarchical plan document";

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
    public PlannedCompaction plan(CompactionRequest request) {
        CompactionRequest checked = Objects.requireNonNull(request, "request");
        executions.assertOwnedAndWithinBudget(checked.execution());
        HierarchicalCompactionPlan plan = planner.plan(checked.planningInput());
        List<PlannedCandidate> candidates = plannedCandidates(checked, plan);
        PlannedCandidate coldCandidate = plannedColdCandidate(checked, plan);
        HierarchicalCheckpoint checkpoint = checked.planningInput().checkpoint();

        // job 只冻结 fencing、范围、ID 和预算；历史消息及摘要正文继续由原有内存/不可变存储持有。
        String planDocument = planDocument(checked, plan, candidates, coldCandidate);
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
                        planDocument,
                        HierarchicalCompactionJob.Status.PLANNED,
                        null,
                        0L);
        HierarchicalCompactionJob persisted = jobs.insertOrSelect(candidate);

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
        return new PlannedCompaction(persisted, checked, plan, candidates, coldCandidate);
    }

    private List<PlannedCandidate> plannedCandidates(
            CompactionRequest request, HierarchicalCompactionPlan plan) {
        Map<RangeKey, PlannedCandidate> ordered = new LinkedHashMap<>();
        List<SummaryRange> targets = new ArrayList<>();
        targets.addAll(plan.coldPromotionRanges());
        targets.addAll(plan.frontier().missingRanges());
        targets.addAll(plan.hotDegradationRanges());
        for (SummaryRange target : targets) {
            addBottomUp(request, target, ordered);
        }
        return List.copyOf(ordered.values());
    }

    private void addBottomUp(
            CompactionRequest request,
            SummaryRange range,
            Map<RangeKey, PlannedCandidate> ordered) {
        RangeKey key = RangeKey.from(range);
        if (ordered.containsKey(key)) {
            return;
        }
        GenerationRange generationRange = generationRange(range);
        String nodeId = existingNodeId(request.planningInput().availableNodes(), range);
        if (nodeId != null) {
            SourceKind kind = range.level() == 0 ? SourceKind.LEAF : SourceKind.PARENT;
            ordered.put(key, new PlannedCandidate(nodeId, generationRange, kind));
            return;
        }
        if (range.level() > 0) {
            addBottomUp(request, childRange(request.planningInput(), range, false), ordered);
            addBottomUp(request, childRange(request.planningInput(), range, true), ordered);
        }
        SourceKind kind = range.level() == 0 ? SourceKind.LEAF : SourceKind.PARENT;
        nodeId =
                generator.nodeKey(
                        request.execution().conversationSessionId(),
                        generationRange,
                        kind,
                        request.policySnapshot());
        ordered.put(key, new PlannedCandidate(nodeId, generationRange, kind));
    }

    private PlannedCandidate plannedColdCandidate(
            CompactionRequest request, HierarchicalCompactionPlan plan) {
        if (plan.coldPromotionRanges().isEmpty()) {
            return null;
        }
        List<com.datagent.agentv2.context.hierarchical.domain.ConversationTurn> turns =
                request.planningInput().turns();
        long endTurn = plan.coldTarget().endTurn();
        GenerationRange range =
                new GenerationRange(
                        1L,
                        endTurn,
                        turns.getFirst().startMessageSequence(),
                        turns.get(Math.toIntExact(endTurn - 1L)).endMessageSequence(),
                        0);
        String nodeId =
                generator.nodeKey(
                        request.execution().conversationSessionId(),
                        range,
                        SourceKind.COLD,
                        request.policySnapshot());
        return new PlannedCandidate(nodeId, range, SourceKind.COLD);
    }

    private String planDocument(
            CompactionRequest request,
            HierarchicalCompactionPlan plan,
            List<PlannedCandidate> candidates,
            PlannedCandidate coldCandidate) {
        HierarchicalCheckpoint checkpoint = request.planningInput().checkpoint();
        PlanDocument document =
                new PlanDocument(
                        PLAN_SCHEMA_VERSION,
                        checkpoint.version(),
                        checkpoint.strategyGeneration(),
                        request.execution().inputUpToMessageSequence(),
                        request.policySnapshot()
                                .targetInputTokens(request.planningInput().budget()),
                        request.maxSummaryTokens(),
                        plan.coldTarget(),
                        plan.warmTarget(),
                        plan.hotTarget(),
                        candidates,
                        coldCandidate);
        try {
            return json.writeValueAsString(document);
        } catch (RuntimeException failure) {
            throw new IllegalStateException(INVALID_PLAN_DOCUMENT, failure);
        }
    }

    private static String existingNodeId(
            List<HierarchicalSummaryNode> availableNodes, SummaryRange range) {
        return availableNodes.stream()
                .filter(
                        node ->
                                node.level() == range.level()
                                        && node.startConversationMessageSequence()
                                                == range.startMessageSequence()
                                        && node.endConversationMessageSequence()
                                                == range.endMessageSequence())
                .map(HierarchicalSummaryNode::nodeId)
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

    /** 规划入口只引用 assembler 已冻结的输入，不复制其中的消息或摘要正文。 */
    record CompactionRequest(
            ContextExecutionRef execution,
            PlanningInput planningInput,
            HierarchicalContextPolicySnapshot policySnapshot,
            int maxSummaryTokens) {

        CompactionRequest {
            execution = Objects.requireNonNull(execution, "execution");
            planningInput = Objects.requireNonNull(planningInput, "planningInput");
            policySnapshot = Objects.requireNonNull(policySnapshot, "policySnapshot");
            if (maxSummaryTokens <= 0) {
                throw new IllegalArgumentException("maxSummaryTokens must be positive");
            }
        }
    }

    /** 生成阶段只追加计划候选元数据，并继续引用同一冻结请求和纯规划结果。 */
    record PlannedCompaction(
            HierarchicalCompactionJob job,
            CompactionRequest request,
            HierarchicalCompactionPlan plan,
            List<PlannedCandidate> candidates,
            PlannedCandidate coldCandidate) {

        PlannedCompaction {
            Objects.requireNonNull(job, "job");
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(plan, "plan");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        }
    }

    /** 单个候选只描述确定性 ID、可信范围和来源类别。 */
    record PlannedCandidate(String nodeId, GenerationRange range, SourceKind sourceKind) {

        PlannedCandidate {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(range, "range");
            Objects.requireNonNull(sourceKind, "sourceKind");
        }

        PlannedCandidate(String nodeId, SummaryRange range, SourceKind sourceKind) {
            this(nodeId, generationRange(range), sourceKind);
        }
    }

    /** body-free JSON 文档冻结发布所需的版本、预算、层级边界和候选 ID。 */
    private record PlanDocument(
            int schemaVersion,
            long baseCheckpointVersion,
            long baseStrategyGeneration,
            long inputUpToMessageSequence,
            int targetInputTokens,
            int maxSummaryTokens,
            HierarchicalCompactionPlan.TurnRange coldTarget,
            HierarchicalCompactionPlan.TurnRange warmTarget,
            HierarchicalCompactionPlan.TurnRange hotTarget,
            List<PlannedCandidate> candidates,
            PlannedCandidate coldCandidate) {}

    private record RangeKey(long startTurn, long endTurn, int level) {

        private static RangeKey from(SummaryRange range) {
            return new RangeKey(range.startTurn(), range.endTurn(), range.level());
        }
    }
}
