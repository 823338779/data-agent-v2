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
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalGenerationSourceProvider.GenerationSourceKey;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalGenerationSourceProvider.GenerationSources;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.ColdSummary;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GeneratedSummary;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GenerationRange;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GenerationRequest;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.SourceKind;
import com.datagent.agentv2.context.hierarchical.summary.SummaryGenerationIdentity;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextSummaryModelIdentity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 在数据库事务外 bottom-up 生成并幂等持久化一次计划的全部候选。 */
public class HierarchicalCandidateGenerationService {

    private static final String SAFE_FAILURE_MESSAGE = "Hierarchical context compaction failed";
    private static final String INCOMPLETE_CANDIDATES = "Hierarchical candidates are incomplete";
    private static final String INVALID_JOB_STATE = "Hierarchical job cannot start generation";
    private static final String INVALID_GENERATION_SOURCES =
            "Hierarchical generation sources do not match the durable plan";

    private final HierarchicalSummaryGenerator generator;
    private final HierarchicalSummaryNodeRepository nodes;
    private final HierarchicalCompactionJobRepository jobs;
    private final ContextExecutionControlPort executions;
    private final HierarchicalGenerationSourceProvider sources;

    /** 固定纯生成器、不可变节点仓储、job 仓储、execution control 和临时来源 provider。 */
    public HierarchicalCandidateGenerationService(
            HierarchicalSummaryGenerator generator,
            HierarchicalSummaryNodeRepository nodes,
            HierarchicalCompactionJobRepository jobs,
            ContextExecutionControlPort executions,
            HierarchicalGenerationSourceProvider sources) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    /** Spring 代理显式挂起调用方事务，确保模型调用和整个生成阶段均无数据库事务。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
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
            // 即使全部候选已 durable，也先执行 execution ownership/cancellation 门禁。
            assertExecution(execution);
            GenerationSources generationSources = loadSources(planned);
            Map<String, HierarchicalSummaryNode> available = loadAndValidateNodes(planned);
            for (PlannedCandidate candidate : planned.candidates()) {
                if (!available.containsKey(candidate.nodeId())) {
                    HierarchicalSummaryNode generated =
                            generateNode(planned, candidate, available, generationSources.turns());
                    HierarchicalSummaryNode persisted = nodes.insertOrSelect(generated);
                    validateNode(planned, candidate, persisted);
                    available.put(candidate.nodeId(), persisted);
                }
            }

            // Cold 正文持久化在 job 私有 JSONB 中；重复恢复时复验完整 envelope 后复用。
            if (planned.coldCandidate() != null) {
                ensureColdCandidate(planned, available, generationSources.checkpoint());
            }
            assertComplete(planned, available);

            // READY 是新的 durable visibility fence，转换前再次检查 owner/budget/cancellation。
            assertExecution(execution);
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
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException(INCOMPLETE_CANDIDATES, failure);
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

    private GenerationSources loadSources(PlannedCompaction planned) {
        HierarchicalCompactionJob job = planned.job();
        GenerationSources loaded =
                Objects.requireNonNull(
                        sources.load(
                                new GenerationSourceKey(
                                        planned.request().execution(),
                                        job.baseCheckpointVersion(),
                                        job.baseStrategyGeneration())),
                        "generation sources");
        HierarchicalCheckpoint checkpoint = loaded.checkpoint();
        boolean futureMessage =
                loaded.turns().stream()
                        .anyMatch(
                                turn -> turn.endMessageSequence() > job.inputUpToMessageSequence());
        if (!checkpoint.conversationSessionId().equals(job.conversationSessionId())
                || checkpoint.version() != job.baseCheckpointVersion()
                || checkpoint.strategyGeneration() != job.baseStrategyGeneration()
                || futureMessage) {
            throw new IllegalStateException(INVALID_GENERATION_SOURCES);
        }
        return loaded;
    }

    private Map<String, HierarchicalSummaryNode> loadAndValidateNodes(PlannedCompaction planned) {
        List<String> plannedIds =
                planned.candidates().stream().map(PlannedCandidate::nodeId).toList();
        Map<String, HierarchicalSummaryNode> available = new LinkedHashMap<>();
        nodes.findByIds(planned.job().conversationSessionId(), plannedIds)
                .forEach(node -> available.put(node.nodeId(), node));
        for (PlannedCandidate candidate : planned.candidates()) {
            HierarchicalSummaryNode node = available.get(candidate.nodeId());
            if (node != null) {
                validateNode(planned, candidate, node);
            }
        }
        return available;
    }

    private HierarchicalSummaryNode generateNode(
            PlannedCompaction planned,
            PlannedCandidate candidate,
            Map<String, HierarchicalSummaryNode> available,
            List<ConversationTurn> turns) {
        GenerationRequest request = generationRequest(planned, candidate, available, turns);
        GeneratedSummary generated =
                generator.generate(request, () -> assertExecution(planned.request().execution()));
        if (!candidate.nodeId().equals(generated.nodeKey())
                || !identity(planned, candidate).equals(generated.identity())) {
            throw new IllegalStateException(INCOMPLETE_CANDIDATES);
        }
        return new HierarchicalSummaryNode(
                candidate.nodeId(),
                generated.identity().conversationSessionId(),
                candidate.range().level(),
                1L << candidate.range().level(),
                candidate.range().startMessageSequence(),
                candidate.range().endMessageSequence(),
                candidate.leftChildNodeId(),
                candidate.rightChildNodeId(),
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
            Map<String, HierarchicalSummaryNode> available,
            List<ConversationTurn> turns) {
        if (candidate.sourceKind() == SourceKind.LEAF) {
            List<ConversationTurn> sourceTurns =
                    turns.stream()
                            .filter(
                                    turn ->
                                            turn.ordinal() >= candidate.range().startTurn()
                                                    && turn.ordinal()
                                                            <= candidate.range().endTurn())
                            .toList();
            return GenerationRequest.leaf(
                    planned.job().conversationSessionId(),
                    candidate.range(),
                    sourceTurns,
                    planned.request().policySnapshot(),
                    planned.request().maxSummaryTokens());
        }
        HierarchicalSummaryNode left = requiredNode(candidate.leftChildNodeId(), available);
        HierarchicalSummaryNode right = requiredNode(candidate.rightChildNodeId(), available);
        return GenerationRequest.parent(
                planned.job().conversationSessionId(),
                candidate.range(),
                left,
                right,
                planned.request().policySnapshot(),
                planned.request().maxSummaryTokens());
    }

    private void ensureColdCandidate(
            PlannedCompaction planned,
            Map<String, HierarchicalSummaryNode> available,
            HierarchicalCheckpoint checkpoint) {
        HierarchicalColdCandidate existing = jobs.findColdCandidate(planned.job().jobId());
        if (existing != null) {
            validateCold(planned, existing);
            return;
        }
        List<HierarchicalSummaryNode> promoted =
                planned.plan().coldPromotionRanges().stream()
                        .map(range -> nodeFor(planned, generationRange(range), available))
                        .toList();
        GenerationRequest request =
                GenerationRequest.cold(
                        planned.job().conversationSessionId(),
                        planned.coldCandidate().range(),
                        oldCold(checkpoint, planned),
                        promoted,
                        planned.request().policySnapshot(),
                        planned.request().maxSummaryTokens());
        GeneratedSummary generated =
                generator.generate(request, () -> assertExecution(planned.request().execution()));
        if (!planned.coldCandidate().nodeId().equals(generated.nodeKey())
                || !identity(planned, planned.coldCandidate()).equals(generated.identity())) {
            throw new IllegalStateException(INCOMPLETE_CANDIDATES);
        }
        HierarchicalColdCandidate candidate = coldCandidate(generated);
        if (!jobs.storeColdCandidate(
                planned.job().jobId(), planned.request().execution(), candidate)) {
            throw new IllegalStateException(INCOMPLETE_CANDIDATES);
        }
        HierarchicalColdCandidate persisted = jobs.findColdCandidate(planned.job().jobId());
        if (persisted == null) {
            throw new IllegalStateException(INCOMPLETE_CANDIDATES);
        }
        validateCold(planned, persisted);
    }

    private void validateNode(
            PlannedCompaction planned, PlannedCandidate descriptor, HierarchicalSummaryNode node) {
        boolean metadataMatches =
                descriptor.nodeId().equals(node.nodeId())
                        && planned.job()
                                .conversationSessionId()
                                .equals(node.conversationSessionId())
                        && descriptor.range().level() == node.level()
                        && descriptor.range().startMessageSequence()
                                == node.startConversationMessageSequence()
                        && descriptor.range().endMessageSequence()
                                == node.endConversationMessageSequence()
                        && (1L << descriptor.range().level()) == node.coveredCompletedTurnCount()
                        && Objects.equals(descriptor.leftChildNodeId(), node.leftChildNodeId())
                        && Objects.equals(descriptor.rightChildNodeId(), node.rightChildNodeId())
                        && descriptor.schemaVersion() == node.summarySchemaVersion()
                        && descriptor.promptVersion() == node.summaryPromptVersion()
                        && descriptor.modelProvider().equals(node.modelProvider())
                        && descriptor.modelName().equals(node.modelName());
        if (!metadataMatches) {
            throw new IllegalStateException(INCOMPLETE_CANDIDATES);
        }
        generator.validateDurableNode(
                node,
                descriptor.range(),
                identity(planned, descriptor),
                planned.request().maxSummaryTokens());
    }

    private void validateCold(PlannedCompaction planned, HierarchicalColdCandidate cold) {
        PlannedCandidate descriptor = planned.coldCandidate();
        boolean metadataMatches =
                descriptor.nodeId().equals(cold.candidateId())
                        && descriptor.range().startTurn() == cold.startTurn()
                        && descriptor.range().endTurn() == cold.endTurn()
                        && descriptor.range().startMessageSequence() == cold.startMessageSequence()
                        && descriptor.range().endMessageSequence() == cold.endMessageSequence()
                        && descriptor.schemaVersion() == cold.summarySchemaVersion()
                        && descriptor.promptVersion() == cold.summaryPromptVersion()
                        && descriptor.modelProvider().equals(cold.modelProvider())
                        && descriptor.modelName().equals(cold.modelName())
                        && cold.estimatedTokenCount() <= planned.request().maxSummaryTokens();
        if (!metadataMatches) {
            throw new IllegalStateException(INCOMPLETE_CANDIDATES);
        }
        generator.validateDurableCold(
                cold,
                descriptor.range(),
                identity(planned, descriptor),
                planned.request().maxSummaryTokens());
    }

    private static ColdSummary oldCold(
            HierarchicalCheckpoint checkpoint, PlannedCompaction planned) {
        if (checkpoint.summarizedCompletedTurnCount() == 0L) {
            return null;
        }
        return new ColdSummary(
                checkpoint.conversationSessionId(),
                new GenerationRange(
                        1L,
                        checkpoint.summarizedCompletedTurnCount(),
                        planned.coldCandidate().range().startMessageSequence(),
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

    private void assertComplete(
            PlannedCompaction planned, Map<String, HierarchicalSummaryNode> available) {
        for (PlannedCandidate candidate : planned.candidates()) {
            HierarchicalSummaryNode node = available.get(candidate.nodeId());
            if (node == null) {
                throw new IllegalStateException(INCOMPLETE_CANDIDATES);
            }
            validateNode(planned, candidate, node);
        }
        if (planned.coldCandidate() != null) {
            HierarchicalColdCandidate cold = jobs.findColdCandidate(planned.job().jobId());
            if (cold == null) {
                throw new IllegalStateException(INCOMPLETE_CANDIDATES);
            }
            validateCold(planned, cold);
        }
    }

    private static HierarchicalSummaryNode requiredNode(
            String nodeId, Map<String, HierarchicalSummaryNode> available) {
        HierarchicalSummaryNode node = available.get(nodeId);
        if (node == null) {
            throw new IllegalStateException(INCOMPLETE_CANDIDATES);
        }
        return node;
    }

    private static HierarchicalSummaryNode nodeFor(
            PlannedCompaction planned,
            GenerationRange range,
            Map<String, HierarchicalSummaryNode> available) {
        PlannedCandidate descriptor =
                planned.candidates().stream()
                        .filter(candidate -> candidate.range().equals(range))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(INCOMPLETE_CANDIDATES));
        return requiredNode(descriptor.nodeId(), available);
    }

    private static SummaryGenerationIdentity identity(
            PlannedCompaction planned, PlannedCandidate candidate) {
        return new SummaryGenerationIdentity(
                planned.job().conversationSessionId(),
                candidate.schemaVersion(),
                candidate.promptVersion(),
                new ContextSummaryModelIdentity(candidate.modelProvider(), candidate.modelName()));
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
