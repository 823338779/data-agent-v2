package com.datagent.agentv2.context.hierarchical.runtime;

import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalColdCandidate;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionJob;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionPlan;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalSummaryNode;
import com.datagent.agentv2.context.hierarchical.domain.SummaryRange;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCompactionJobRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalSummaryNodeRepository;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction.PlannedCandidate;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction.PlannedCompaction;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.ColdSummary;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GeneratedSummary;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GenerationRange;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GenerationRequest;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.SourceKind;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 在数据库事务外 bottom-up 生成并幂等持久化一次计划的全部候选。 */
public final class HierarchicalCandidateGenerationService {

    private static final String SAFE_FAILURE_MESSAGE = "Hierarchical context compaction failed";
    private static final String INCOMPLETE_CANDIDATES = "Hierarchical candidates are incomplete";
    private static final String INVALID_JOB_STATE = "Hierarchical job cannot start generation";

    private final HierarchicalSummaryGenerator generator;
    private final HierarchicalSummaryNodeRepository nodes;
    private final HierarchicalCompactionJobRepository jobs;
    private final ContextExecutionControlPort executions;

    /** 固定纯生成器、不可变节点仓储、job 仓储和 execution control 依赖。 */
    public HierarchicalCandidateGenerationService(
            HierarchicalSummaryGenerator generator,
            HierarchicalSummaryNodeRepository nodes,
            HierarchicalCompactionJobRepository jobs,
            ContextExecutionControlPort executions) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.executions = Objects.requireNonNull(executions, "executions");
    }

    /** 不建立事务；模型调用之间仅执行短仓储操作和 execution fencing。 */
    public ReadyCompaction generate(PlannedCompaction plannedCompaction) {
        PlannedCompaction planned = Objects.requireNonNull(plannedCompaction, "plannedCompaction");
        ContextExecutionRef execution = planned.request().execution();
        String jobId = planned.job().jobId();
        if (!jobs.transitionJob(
                jobId,
                HierarchicalCompactionJob.Status.PLANNED,
                HierarchicalCompactionJob.Status.GENERATING,
                execution,
                null)) {
            throw new IllegalStateException(INVALID_JOB_STATE);
        }

        try {
            // 一次批量读取复用所有已 durable 的计划节点，后续只生成真正缺失的候选。
            List<String> plannedIds =
                    planned.candidates().stream().map(PlannedCandidate::nodeId).toList();
            Map<String, HierarchicalSummaryNode> available = new LinkedHashMap<>();
            nodes.findByIds(execution.conversationSessionId(), plannedIds)
                    .forEach(node -> available.put(node.nodeId(), node));
            for (PlannedCandidate candidate : planned.candidates()) {
                if (!available.containsKey(candidate.nodeId())) {
                    HierarchicalSummaryNode generated = generateNode(planned, candidate, available);
                    HierarchicalSummaryNode persisted = nodes.insertOrSelect(generated);
                    if (!candidate.nodeId().equals(persisted.nodeId())) {
                        throw new IllegalStateException(INCOMPLETE_CANDIDATES);
                    }
                    available.put(candidate.nodeId(), persisted);
                }
            }

            // Cold 正文持久化在 job 私有 JSONB 中；重复恢复时同候选直接复用且不再次调用模型。
            if (planned.coldCandidate() != null) {
                ensureColdCandidate(planned, available);
            }
            assertComplete(planned, available);
            if (!jobs.transitionJob(
                    jobId,
                    HierarchicalCompactionJob.Status.GENERATING,
                    HierarchicalCompactionJob.Status.READY_TO_PUBLISH,
                    execution,
                    null)) {
                throw new IllegalStateException(INCOMPLETE_CANDIDATES);
            }
            return ready(planned, false);
        } catch (CancellationDetected ignored) {
            jobs.transitionJob(
                    jobId,
                    HierarchicalCompactionJob.Status.GENERATING,
                    HierarchicalCompactionJob.Status.CANCELLED,
                    execution,
                    null);
            return ready(planned, true);
        } catch (IllegalStateException failure) {
            if (HierarchicalSummaryGenerator.FAILURE_CONTEXT_COMPACTION.equals(
                    failure.getMessage())) {
                failJob(planned, failure);
                throw new HierarchicalCompactionException(
                        HierarchicalSummaryGenerator.FAILURE_CONTEXT_COMPACTION, failure);
            }
            throw failure;
        }
    }

    private HierarchicalSummaryNode generateNode(
            PlannedCompaction planned,
            PlannedCandidate candidate,
            Map<String, HierarchicalSummaryNode> available) {
        GenerationRequest request = generationRequest(planned, candidate, available);
        GeneratedSummary generated =
                generator.generate(request, () -> assertExecution(planned.request().execution()));
        if (!candidate.nodeId().equals(generated.nodeKey())) {
            throw new IllegalStateException(INCOMPLETE_CANDIDATES);
        }
        String leftId = null;
        String rightId = null;
        if (candidate.sourceKind() == SourceKind.PARENT) {
            List<HierarchicalSummaryNode> children = children(candidate.range(), available);
            leftId = children.get(0).nodeId();
            rightId = children.get(1).nodeId();
        }
        return new HierarchicalSummaryNode(
                candidate.nodeId(),
                generated.identity().conversationSessionId(),
                candidate.range().level(),
                1L << candidate.range().level(),
                candidate.range().startMessageSequence(),
                candidate.range().endMessageSequence(),
                leftId,
                rightId,
                generated.document(),
                generated.estimatedTokenCount(),
                generated.identity().schemaVersion(),
                generated.identity().promptVersion(),
                generated.identity().modelIdentity().provider(),
                generated.identity().modelIdentity().model());
    }

    private GenerationRequest generationRequest(
            PlannedCompaction planned,
            PlannedCandidate candidate,
            Map<String, HierarchicalSummaryNode> available) {
        if (candidate.sourceKind() == SourceKind.LEAF) {
            List<ConversationTurn> turns =
                    planned.request().planningInput().turns().stream()
                            .filter(
                                    turn ->
                                            turn.ordinal() >= candidate.range().startTurn()
                                                    && turn.ordinal()
                                                            <= candidate.range().endTurn())
                            .toList();
            return GenerationRequest.leaf(
                    planned.request().execution().conversationSessionId(),
                    candidate.range(),
                    turns,
                    planned.request().policySnapshot(),
                    planned.request().maxSummaryTokens());
        }
        List<HierarchicalSummaryNode> children = children(candidate.range(), available);
        return GenerationRequest.parent(
                planned.request().execution().conversationSessionId(),
                candidate.range(),
                children.get(0),
                children.get(1),
                planned.request().policySnapshot(),
                planned.request().maxSummaryTokens());
    }

    private void ensureColdCandidate(
            PlannedCompaction planned, Map<String, HierarchicalSummaryNode> available) {
        HierarchicalColdCandidate existing = jobs.findColdCandidate(planned.job().jobId());
        if (existing != null) {
            if (!existing.candidateId().equals(planned.coldCandidate().nodeId())) {
                throw new IllegalStateException(INCOMPLETE_CANDIDATES);
            }
            return;
        }
        List<HierarchicalSummaryNode> promoted =
                planned.plan().coldPromotionRanges().stream()
                        .map(range -> nodeFor(generationRange(range), available))
                        .toList();
        GenerationRequest request =
                GenerationRequest.cold(
                        planned.request().execution().conversationSessionId(),
                        planned.coldCandidate().range(),
                        oldCold(planned.request().planningInput().checkpoint(), planned),
                        promoted,
                        planned.request().policySnapshot(),
                        planned.request().maxSummaryTokens());
        GeneratedSummary generated =
                generator.generate(request, () -> assertExecution(planned.request().execution()));
        if (!planned.coldCandidate().nodeId().equals(generated.nodeKey())) {
            throw new IllegalStateException(INCOMPLETE_CANDIDATES);
        }
        HierarchicalColdCandidate candidate = coldCandidate(generated);
        if (!jobs.storeColdCandidate(
                planned.job().jobId(), planned.request().execution(), candidate)) {
            throw new IllegalStateException(INCOMPLETE_CANDIDATES);
        }
    }

    private static ColdSummary oldCold(
            HierarchicalCheckpoint checkpoint, PlannedCompaction planned) {
        if (checkpoint.summarizedCompletedTurnCount() == 0L) {
            return null;
        }
        long startMessage =
                planned.request().planningInput().turns().getFirst().startMessageSequence();
        return new ColdSummary(
                checkpoint.conversationSessionId(),
                new GenerationRange(
                        1L,
                        checkpoint.summarizedCompletedTurnCount(),
                        startMessage,
                        checkpoint.summarizedUpToConversationMessageSequence(),
                        0),
                checkpoint.coldSummaryDocument(),
                checkpoint.estimatedTokenCount(),
                checkpoint.summarySchemaVersion(),
                checkpoint.summaryPromptVersion(),
                checkpoint.modelProvider(),
                checkpoint.modelName());
    }

    private static HierarchicalColdCandidate coldCandidate(GeneratedSummary generated) {
        return new HierarchicalColdCandidate(
                generated.nodeKey(),
                generated.range().startTurn(),
                generated.range().endTurn(),
                generated.range().startMessageSequence(),
                generated.range().endMessageSequence(),
                generated.document(),
                generated.estimatedTokenCount(),
                generated.identity().schemaVersion(),
                generated.identity().promptVersion(),
                generated.identity().modelIdentity().provider(),
                generated.identity().modelIdentity().model());
    }

    private void assertExecution(ContextExecutionRef execution) {
        executions.assertOwnedAndWithinBudget(execution);
        if (executions.cancelIfRequested(execution)) {
            throw new CancellationDetected();
        }
    }

    private static List<HierarchicalSummaryNode> children(
            GenerationRange parent, Map<String, HierarchicalSummaryNode> available) {
        int childLevel = parent.level() - 1;
        long childTurns = 1L << childLevel;
        long rightStartTurn = parent.startTurn() + childTurns;
        HierarchicalSummaryNode left =
                available.values().stream()
                        .filter(
                                node ->
                                        node.level() == childLevel
                                                && node.startConversationMessageSequence()
                                                        == parent.startMessageSequence()
                                                && node.coveredCompletedTurnCount() == childTurns)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(INCOMPLETE_CANDIDATES));
        HierarchicalSummaryNode right =
                available.values().stream()
                        .filter(
                                node ->
                                        node.level() == childLevel
                                                && node.startConversationMessageSequence()
                                                        == left.endConversationMessageSequence()
                                                                + 1L
                                                && node.coveredCompletedTurnCount()
                                                        == parent.endTurn() - rightStartTurn + 1L)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(INCOMPLETE_CANDIDATES));
        return List.of(left, right);
    }

    private static HierarchicalSummaryNode nodeFor(
            GenerationRange range, Map<String, HierarchicalSummaryNode> available) {
        return available.values().stream()
                .filter(
                        node ->
                                node.level() == range.level()
                                        && node.startConversationMessageSequence()
                                                == range.startMessageSequence()
                                        && node.endConversationMessageSequence()
                                                == range.endMessageSequence())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(INCOMPLETE_CANDIDATES));
    }

    private void assertComplete(
            PlannedCompaction planned, Map<String, HierarchicalSummaryNode> available) {
        if (planned.candidates().stream()
                .map(PlannedCandidate::nodeId)
                .anyMatch(nodeId -> !available.containsKey(nodeId))) {
            throw new IllegalStateException(INCOMPLETE_CANDIDATES);
        }
        if (planned.coldCandidate() != null) {
            HierarchicalColdCandidate cold = jobs.findColdCandidate(planned.job().jobId());
            if (cold == null || !cold.candidateId().equals(planned.coldCandidate().nodeId())) {
                throw new IllegalStateException(INCOMPLETE_CANDIDATES);
            }
        }
    }

    private void failJob(PlannedCompaction planned, RuntimeException failure) {
        String failureCode = HierarchicalSummaryGenerator.FAILURE_CONTEXT_COMPACTION;
        jobs.transitionJob(
                planned.job().jobId(),
                HierarchicalCompactionJob.Status.GENERATING,
                HierarchicalCompactionJob.Status.FAILED,
                planned.request().execution(),
                failureCode);
        executions.fail(planned.request().execution(), failureCode, SAFE_FAILURE_MESSAGE);
    }

    private static ReadyCompaction ready(PlannedCompaction planned, boolean cancelled) {
        return new ReadyCompaction(
                planned.job().jobId(),
                planned.plan(),
                planned.candidates().stream().map(PlannedCandidate::nodeId).toList(),
                planned.coldCandidate() == null ? null : planned.coldCandidate().nodeId(),
                cancelled);
    }

    private static GenerationRange generationRange(SummaryRange range) {
        return new GenerationRange(
                range.startTurn(),
                range.endTurn(),
                range.startMessageSequence(),
                range.endMessageSequence(),
                range.level());
    }

    /** READY 结果只携带 job、范围规划和 durable candidate ID，不复制任何正文。 */
    record ReadyCompaction(
            String jobId,
            HierarchicalCompactionPlan plan,
            List<String> nodeIds,
            String coldCandidateId,
            boolean cancelled) {

        ReadyCompaction {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(plan, "plan");
            nodeIds = List.copyOf(Objects.requireNonNull(nodeIds, "nodeIds"));
        }
    }

    private static final class CancellationDetected extends RuntimeException {}
}
