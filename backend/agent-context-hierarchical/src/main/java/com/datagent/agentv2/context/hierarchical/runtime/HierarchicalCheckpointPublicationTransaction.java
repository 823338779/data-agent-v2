package com.datagent.agentv2.context.hierarchical.runtime;

import com.datagent.agentv2.context.hierarchical.domain.FrontierSelection;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalColdCandidate;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionJob;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalSummaryNode;
import com.datagent.agentv2.context.hierarchical.domain.SummaryNodeIdentity;
import com.datagent.agentv2.context.hierarchical.domain.SummaryRange;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCheckpointRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCompactionJobRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalSummaryNodeRepository;
import com.datagent.agentv2.context.hierarchical.planning.HierarchicalInvariantValidator;
import com.datagent.agentv2.context.hierarchical.planning.WarmFrontierSelector;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCandidateGenerationService.ReadyCompaction;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction.PlannedCandidate;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction.PlannedCompaction;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GenerationRange;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort.ContextLifecycleEvent;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 在独立短事务中校验 READY 候选并原子发布 checkpoint、job 和完成事件。 */
public class HierarchicalCheckpointPublicationTransaction {

    private static final int FRONTIER_SCHEMA_VERSION = 1;
    private static final String FRONTIER_SCHEMA_VERSION_FIELD = "schemaVersion";
    private static final String FRONTIER_NODES_FIELD = "nodes";
    private static final String EVENT_JOB_ID = "compactionJobId";
    private static final String EVENT_GENERATION = "strategyGeneration";
    private static final String INVALID_PUBLICATION = "Invalid hierarchical checkpoint publication";
    private static final WarmFrontierSelector FRONTIER_SELECTOR = new WarmFrontierSelector();

    private final HierarchicalCheckpointRepository checkpoints;
    private final HierarchicalCompactionJobRepository jobs;
    private final HierarchicalSummaryNodeRepository nodes;
    private final HierarchicalCompactionPlanningTransaction planning;
    private final HierarchicalCandidateGenerationService generation;
    private final ContextExecutionControlPort executions;
    private final HierarchicalInvariantValidator invariants;
    private final ObjectMapper json;
    private final HierarchicalDurablePlanValidator planValidator =
            new HierarchicalDurablePlanValidator();

    /** 固定 publication 所需的持久化、durable plan、执行门禁和不变量依赖。 */
    public HierarchicalCheckpointPublicationTransaction(
            HierarchicalCheckpointRepository checkpoints,
            HierarchicalCompactionJobRepository jobs,
            HierarchicalSummaryNodeRepository nodes,
            HierarchicalCompactionPlanningTransaction planning,
            HierarchicalCandidateGenerationService generation,
            ContextExecutionControlPort executions,
            HierarchicalInvariantValidator invariants,
            ObjectMapper json) {
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.planning = Objects.requireNonNull(planning, "planning");
        this.generation = Objects.requireNonNull(generation, "generation");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.invariants = Objects.requireNonNull(invariants, "invariants");
        this.json = Objects.requireNonNull(json, "json");
    }

    /** 仅对 publication 前的不变量异常提交 FAILED；CAS 之后的异常仍按默认规则整体回滚。 */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = HierarchicalCompactionException.class)
    public PublishResult publish(ReadyCompaction suppliedReady) {
        ReadyCompaction supplied = Objects.requireNonNull(suppliedReady, "suppliedReady");
        PlannedCompaction caller = supplied.planned();
        executions.assertOwnedAndWithinBudget(caller.execution());

        // 内存 carrier 不具备授权性；始终重读 exact job，并只使用持久化 schema-v3 plan。
        HierarchicalCompactionJob job = jobs.findJob(caller.job().jobId());
        if (!ownedReady(job, caller)) {
            return PublishResult.STALE;
        }
        HierarchicalCheckpoint expected =
                checkpoints.findCheckpoint(caller.execution().conversationSessionId());
        if (expected == null
                || expected.version() != job.baseCheckpointVersion()
                || expected.strategyGeneration() != job.baseStrategyGeneration()) {
            return PublishResult.STALE;
        }
        if (supplied.cancelled() || executions.cancelIfRequested(caller.execution())) {
            jobs.transitionJob(
                    job.jobId(),
                    HierarchicalCompactionJob.Status.READY_TO_PUBLISH,
                    HierarchicalCompactionJob.Status.CANCELLED,
                    caller.execution(),
                    null);
            return PublishResult.STALE;
        }

        HierarchicalCheckpoint replacement;
        try {
            PlannedCompaction durable = planning.restore(job, caller.execution());
            generation.validateReady(durable);
            ReadyCompaction ready = new ReadyCompaction(durable, false);
            validatePublishedView(ready);
            replacement = replacement(expected, ready);
            validateCheckpointIdentity(expected, durable);
            validateMonotonicReplacement(expected, replacement, caller.execution());
        } catch (RuntimeException invalid) {
            failInvalidPublication(job, caller.execution(), invalid);
            throw new AssertionError("unreachable");
        }

        // checkpoint 可见性、终态 job 和共享数据库完成事件位于同一物理事务。
        if (!checkpoints.compareAndSetCheckpoint(
                expected, replacement, job.jobId(), caller.execution())) {
            return PublishResult.STALE;
        }
        if (!jobs.transitionJob(
                job.jobId(),
                HierarchicalCompactionJob.Status.READY_TO_PUBLISH,
                HierarchicalCompactionJob.Status.SUCCEEDED,
                caller.execution(),
                null)) {
            throw new IllegalStateException(INVALID_PUBLICATION);
        }
        executions.publish(
                caller.execution(),
                new ContextLifecycleEvent(
                        ContextLifecycleEvent.Type.COMPLETED,
                        Map.of(
                                EVENT_JOB_ID,
                                job.jobId(),
                                EVENT_GENERATION,
                                replacement.strategyGeneration())));
        return PublishResult.PUBLISHED;
    }

    /** 提供给聚焦测试的纯 publication 视图校验入口。 */
    void validatePublishedView(ReadyCompaction ready) {
        PlannedCompaction planned = Objects.requireNonNull(ready, "ready").planned();
        List<PlannedCandidate> required =
                planValidator.validate(
                        planned.generationSettings(),
                        planned.plan(),
                        planned.candidates(),
                        planned.coldCandidate());
        List<String> requiredIds = required.stream().map(PlannedCandidate::nodeId).toList();
        List<HierarchicalSummaryNode> persisted =
                nodes.findByIds(planned.job().conversationSessionId(), requiredIds);
        if (persisted.size() != Set.copyOf(requiredIds).size()) {
            throw new IllegalArgumentException(INVALID_PUBLICATION);
        }

        // 所有 durable 节点必须与冻结的会话、schema、prompt 和模型身份完全一致。
        Map<String, HierarchicalSummaryNode> byId = new HashMap<>();
        persisted.forEach(node -> byId.put(node.nodeId(), node));
        SummaryNodeIdentity identity =
                new SummaryNodeIdentity(
                        planned.job().conversationSessionId(),
                        planned.generationSettings().schemaVersion(),
                        planned.generationSettings().promptVersion(),
                        planned.generationSettings().modelProvider(),
                        planned.generationSettings().modelName());
        persisted.forEach(identity::validate);

        FrontierSelection frontier = resolvedFrontier(planned, byId);
        List<SummaryRange> targets = targetRanges(planned);
        if (!targets.isEmpty()) {
            invariants.validateFrontier(
                    frontier,
                    persisted,
                    targets,
                    planned.plan().coldTarget().isEmpty()
                            ? 0L
                            : planned.plan().coldTarget().endTurn(),
                    planned.plan().currentIncompleteTurn().orElse(null),
                    identity);
        }
        if (planned.coldCandidate() != null) {
            HierarchicalColdCandidate cold = jobs.findColdCandidate(planned.job().jobId());
            if (cold == null || !cold.candidateId().equals(planned.coldCandidate().nodeId())) {
                throw new IllegalArgumentException(INVALID_PUBLICATION);
            }
        }
    }

    private void failInvalidPublication(
            HierarchicalCompactionJob job,
            ContextExecutionRef execution,
            RuntimeException invalid) {
        if (!jobs.transitionJob(
                job.jobId(),
                HierarchicalCompactionJob.Status.READY_TO_PUBLISH,
                HierarchicalCompactionJob.Status.FAILED,
                execution,
                HierarchicalCompactionException.FAILURE_CODE)) {
            throw new IllegalStateException(INVALID_PUBLICATION, invalid);
        }
        executions.fail(
                execution,
                HierarchicalCompactionException.FAILURE_CODE,
                HierarchicalCompactionException.SAFE_MESSAGE);
        throw new HierarchicalCompactionException(
                HierarchicalCompactionException.FAILURE_CODE, invalid);
    }

    private HierarchicalCheckpoint replacement(
            HierarchicalCheckpoint expected, ReadyCompaction ready) {
        PlannedCompaction planned = ready.planned();
        HierarchicalColdCandidate cold =
                planned.coldCandidate() == null
                        ? null
                        : jobs.findColdCandidate(planned.job().jobId());
        List<HierarchicalSummaryNode> persisted =
                nodes.findByIds(
                        planned.job().conversationSessionId(),
                        planned.candidates().stream().map(PlannedCandidate::nodeId).toList());
        Map<String, HierarchicalSummaryNode> byId = new HashMap<>();
        persisted.forEach(node -> byId.put(node.nodeId(), node));
        FrontierSelection frontier = resolvedFrontier(planned, byId);
        String frontierDocument =
                json.writeValueAsString(
                        Map.of(
                                FRONTIER_SCHEMA_VERSION_FIELD,
                                FRONTIER_SCHEMA_VERSION,
                                FRONTIER_NODES_FIELD,
                                frontier.nodeIds()));
        return new HierarchicalCheckpoint(
                expected.checkpointId(),
                expected.conversationSessionId(),
                cold == null ? expected.coldSummaryDocument() : cold.summaryDocument(),
                cold == null
                        ? expected.summarizedUpToConversationMessageSequence()
                        : cold.endMessageSequence(),
                cold == null ? expected.summarizedCompletedTurnCount() : cold.endTurn(),
                cold == null ? expected.estimatedTokenCount() : cold.estimatedTokenCount(),
                expected.summarySchemaVersion(),
                expected.summaryPromptVersion(),
                expected.modelProvider(),
                expected.modelName(),
                frontierDocument,
                expected.strategyGeneration() + 1L,
                expected.version() + 1L);
    }

    /** 持久化 frontier 复用原选择，并将缺失 Warm 节点和紧急 Hot 叶替换为生成后的 ID。 */
    static FrontierSelection resolvedFrontier(
            PlannedCompaction planned, Map<String, HierarchicalSummaryNode> byId) {
        SummaryNodeIdentity identity =
                new SummaryNodeIdentity(
                        planned.job().conversationSessionId(),
                        planned.generationSettings().schemaVersion(),
                        planned.generationSettings().promptVersion(),
                        planned.generationSettings().modelProvider(),
                        planned.generationSettings().modelName());
        Map<String, SummaryRange> rangesByNodeId = new HashMap<>();
        planned.candidates()
                .forEach(
                        candidate ->
                                rangesByNodeId.put(
                                        candidate.nodeId(), summaryRange(candidate.range())));

        // READY guarantees that every descriptor has a durable node before reselection.
        FrontierSelection warm =
                FRONTIER_SELECTOR.selectResolved(
                        identity,
                        List.copyOf(byId.values()),
                        rangesByNodeId,
                        planned.plan().warmTargetRanges(),
                        planned.warmTokenBudget());
        List<SelectedRange> selected = new ArrayList<>();
        for (int index = 0; index < warm.nodeIds().size(); index++) {
            selected.add(new SelectedRange(warm.nodeIds().get(index), warm.ranges().get(index)));
        }

        // Emergency Hot nodes must form a contiguous prefix beginning with the oldest turn.
        long hotCursor = planned.plan().hotTarget().startTurn();
        List<SummaryRange> hotRanges = new ArrayList<>(planned.plan().hotDegradationRanges());
        hotRanges.sort(Comparator.comparingLong(SummaryRange::startTurn));
        for (SummaryRange hot : hotRanges) {
            if (hot.level() != 0 || hot.startTurn() != hotCursor) {
                throw new IllegalArgumentException(INVALID_PUBLICATION);
            }
            selected.add(new SelectedRange(descriptorForRange(planned, hot).nodeId(), hot));
            hotCursor = hot.endTurn() + 1L;
        }
        if (selected.isEmpty()) {
            return FrontierSelection.empty();
        }
        selected.sort(Comparator.comparingLong(value -> value.range().startTurn()));
        List<String> selectedIds = selected.stream().map(SelectedRange::nodeId).toList();
        List<SummaryRange> selectedRanges = selected.stream().map(SelectedRange::range).toList();
        long tokenCount =
                selectedIds.stream()
                        .map(
                                nodeId -> {
                                    HierarchicalSummaryNode node = byId.get(nodeId);
                                    if (node == null) {
                                        throw new IllegalArgumentException(INVALID_PUBLICATION);
                                    }
                                    return node;
                                })
                        .mapToLong(HierarchicalSummaryNode::estimatedTokenCount)
                        .sum();
        return new FrontierSelection(
                selectedIds,
                selectedRanges,
                List.of(),
                tokenCount >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) tokenCount,
                true,
                true);
    }

    private static PlannedCandidate descriptorForRange(
            PlannedCompaction planned, SummaryRange range) {
        GenerationRange generationRange = generationRange(range);
        return planned.candidates().stream()
                .filter(candidate -> candidate.range().equals(generationRange))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(INVALID_PUBLICATION));
    }

    private static GenerationRange generationRange(SummaryRange range) {
        return new GenerationRange(
                range.startTurn(),
                range.endTurn(),
                range.startMessageSequence(),
                range.endMessageSequence(),
                range.level());
    }

    private static SummaryRange summaryRange(GenerationRange range) {
        return new SummaryRange(
                range.startTurn(),
                range.endTurn(),
                range.startMessageSequence(),
                range.endMessageSequence(),
                range.level());
    }

    private static List<SummaryRange> targetRanges(PlannedCompaction planned) {
        List<SummaryRange> targets = new ArrayList<>(planned.plan().warmTargetRanges());
        targets.addAll(planned.plan().hotDegradationRanges());
        targets.sort(Comparator.comparingLong(SummaryRange::startTurn));
        return List.copyOf(targets);
    }

    private static void validateCheckpointIdentity(
            HierarchicalCheckpoint checkpoint, PlannedCompaction planned) {
        if (checkpoint.summarySchemaVersion() != planned.generationSettings().schemaVersion()
                || checkpoint.summaryPromptVersion() != planned.generationSettings().promptVersion()
                || !checkpoint.modelProvider().equals(planned.generationSettings().modelProvider())
                || !checkpoint.modelName().equals(planned.generationSettings().modelName())) {
            throw new IllegalArgumentException(INVALID_PUBLICATION);
        }
    }

    private static void validateMonotonicReplacement(
            HierarchicalCheckpoint expected,
            HierarchicalCheckpoint replacement,
            ContextExecutionRef execution) {
        if (!expected.checkpointId().equals(replacement.checkpointId())
                || replacement.summarizedUpToConversationMessageSequence()
                        < expected.summarizedUpToConversationMessageSequence()
                || replacement.summarizedCompletedTurnCount()
                        < expected.summarizedCompletedTurnCount()
                || replacement.summarizedUpToConversationMessageSequence()
                        > execution.inputUpToMessageSequence()
                || replacement.version() != expected.version() + 1L
                || replacement.strategyGeneration() != expected.strategyGeneration() + 1L) {
            throw new IllegalArgumentException(INVALID_PUBLICATION);
        }
    }

    private static boolean ownedReady(HierarchicalCompactionJob job, PlannedCompaction supplied) {
        return job != null
                && job.status() == HierarchicalCompactionJob.Status.READY_TO_PUBLISH
                && job.jobId().equals(supplied.job().jobId())
                && job.conversationSessionId().equals(supplied.execution().conversationSessionId())
                && job.executionId().equals(supplied.execution().executionId())
                && job.ownerInstanceId().equals(supplied.execution().ownerInstanceId())
                && job.executionAttempt() == supplied.execution().executionAttempt()
                && job.inputUpToMessageSequence()
                        == supplied.execution().inputUpToMessageSequence();
    }

    private record SelectedRange(String nodeId, SummaryRange range) {

        private SelectedRange {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(range, "range");
        }
    }

    /** publication 的有限 CAS 结果；候选仅在 PUBLISHED 后对正常读视图可见。 */
    enum PublishResult {
        PUBLISHED,
        STALE
    }
}
