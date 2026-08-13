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
    private final HierarchicalDurablePlanValidator planValidator =
            new HierarchicalDurablePlanValidator();

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
        ContextExecutionRef execution = planned.execution();
        String jobId = planned.job().jobId();
        HierarchicalCompactionJob current = jobs.findJob(jobId);
        requireCurrentJob(planned, current);
        if (current.status() == HierarchicalCompactionJob.Status.PLANNED) {
            if (!jobs.transitionJob(
                    jobId,
                    HierarchicalCompactionJob.Status.PLANNED,
                    HierarchicalCompactionJob.Status.GENERATING,
                    execution,
                    null)) {
                throw new IllegalStateException(INVALID_JOB_STATE);
            }
        } else if (current.status() == HierarchicalCompactionJob.Status.READY_TO_PUBLISH) {
            assertExecution(execution);
            List<PlannedCandidate> requiredCandidates = requiredCandidates(planned);
            Map<String, HierarchicalSummaryNode> available =
                    loadAndValidateNodes(planned, requiredCandidates);
            loadAndValidateCold(planned);
            assertComplete(planned, requiredCandidates, available);
            return ready(planned, false);
        } else if (current.status() != HierarchicalCompactionJob.Status.GENERATING) {
            throw new IllegalStateException(INVALID_JOB_STATE);
        }

        try {
            // 即使全部候选已 durable，也先执行 execution ownership/cancellation 门禁。
            assertExecution(execution);
            List<PlannedCandidate> requiredCandidates = requiredCandidates(planned);
            Map<String, HierarchicalSummaryNode> available =
                    loadAndValidateNodes(planned, requiredCandidates);
            HierarchicalColdCandidate durableCold = loadAndValidateCold(planned);
            boolean missingNode =
                    requiredCandidates.stream()
                            .anyMatch(candidate -> !available.containsKey(candidate.nodeId()));
            boolean missingCold = planned.coldCandidate() != null && durableCold == null;

            // 仅在确实需要模型生成时重载短生命周期正文；全 durable 恢复不依赖临时 source。
            GenerationSources generationSources =
                    missingNode || missingCold ? loadSources(planned) : null;
            List<ConversationTurn> turns =
                    generationSources == null ? List.of() : generationSources.turns();
            for (PlannedCandidate candidate : requiredCandidates) {
                if (!available.containsKey(candidate.nodeId())) {
                    HierarchicalSummaryNode generated =
                            generateNode(planned, candidate, available, turns);
                    HierarchicalSummaryNode persisted = nodes.insertOrSelect(generated);
                    validateNode(planned, candidate, persisted);
                    available.put(candidate.nodeId(), persisted);
                }
            }

            // Cold 正文持久化在 job 私有 JSONB 中；重复恢复时复验完整 envelope 后复用。
            if (planned.coldCandidate() != null) {
                HierarchicalCheckpoint checkpoint =
                        generationSources == null ? null : generationSources.checkpoint();
                ensureColdCandidate(planned, available, durableCold, checkpoint);
            }
            assertComplete(planned, requiredCandidates, available);

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
                        HierarchicalCompactionException.FAILURE_CODE, failure);
            }
            throw failure;
        }
    }

    /** publication 前重读并复验全部 durable 节点与 Cold，不加载正文也不调用模型。 */
    void validateReady(PlannedCompaction plannedCompaction) {
        PlannedCompaction planned = Objects.requireNonNull(plannedCompaction, "plannedCompaction");
        HierarchicalCompactionJob current = jobs.findJob(planned.job().jobId());
        requireCurrentJob(planned, current);
        if (current.status() != HierarchicalCompactionJob.Status.READY_TO_PUBLISH) {
            throw new IllegalStateException(INVALID_JOB_STATE);
        }
        List<PlannedCandidate> requiredCandidates = requiredCandidates(planned);
        Map<String, HierarchicalSummaryNode> available =
                loadAndValidateNodes(planned, requiredCandidates);
        loadAndValidateCold(planned);
        assertComplete(planned, requiredCandidates, available);
    }

    private GenerationSources loadSources(PlannedCompaction planned) {
        HierarchicalCompactionJob job = planned.job();
        GenerationSources loaded =
                Objects.requireNonNull(
                        sources.load(
                                new GenerationSourceKey(
                                        planned.execution(),
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

    private List<PlannedCandidate> requiredCandidates(PlannedCompaction planned) {
        return planValidator.validate(
                planned.generationSettings(),
                planned.plan(),
                planned.candidates(),
                planned.coldCandidate());
    }

    private Map<String, HierarchicalSummaryNode> loadAndValidateNodes(
            PlannedCompaction planned, List<PlannedCandidate> requiredCandidates) {
        List<String> plannedIds =
                requiredCandidates.stream().map(PlannedCandidate::nodeId).toList();
        Map<String, HierarchicalSummaryNode> available = new LinkedHashMap<>();
        nodes.findByIds(planned.job().conversationSessionId(), plannedIds)
                .forEach(node -> available.put(node.nodeId(), node));
        for (PlannedCandidate candidate : requiredCandidates) {
            HierarchicalSummaryNode node = available.get(candidate.nodeId());
            if (node != null) {
                validateNode(planned, candidate, node);
            }
        }
        return available;
    }

    private HierarchicalColdCandidate loadAndValidateCold(PlannedCompaction planned) {
        if (planned.coldCandidate() == null) {
            return null;
        }
        if (!planned.generationSettings().matches(planned.coldCandidate())
                || planned.coldCandidate().sourceKind() != SourceKind.COLD) {
            throw new IllegalStateException(INCOMPLETE_CANDIDATES);
        }
        HierarchicalColdCandidate cold = jobs.findColdCandidate(planned.job().jobId());
        if (cold != null) {
            validateCold(planned, cold);
        }
        return cold;
    }

    private HierarchicalSummaryNode generateNode(
            PlannedCompaction planned,
            PlannedCandidate candidate,
            Map<String, HierarchicalSummaryNode> available,
            List<ConversationTurn> turns) {
        GenerationRequest request = generationRequest(planned, candidate, available, turns);
        GeneratedSummary generated =
                generator.generate(request, () -> assertExecution(planned.execution()));
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
                    planned.generationSettings().policySnapshot(),
                    planned.generationSettings().maxSummaryTokens());
        }
        HierarchicalSummaryNode left = requiredNode(candidate.leftChildNodeId(), available);
        HierarchicalSummaryNode right = requiredNode(candidate.rightChildNodeId(), available);
        return GenerationRequest.parent(
                planned.job().conversationSessionId(),
                candidate.range(),
                left,
                right,
                planned.generationSettings().policySnapshot(),
                planned.generationSettings().maxSummaryTokens());
    }

    private void ensureColdCandidate(
            PlannedCompaction planned,
            Map<String, HierarchicalSummaryNode> available,
            HierarchicalColdCandidate existing,
            HierarchicalCheckpoint checkpoint) {
        if (existing != null) {
            return;
        }
        Objects.requireNonNull(checkpoint, "generation checkpoint");
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
                        planned.generationSettings().policySnapshot(),
                        planned.generationSettings().maxSummaryTokens());
        GeneratedSummary generated =
                generator.generate(request, () -> assertExecution(planned.execution()));
        if (!planned.coldCandidate().nodeId().equals(generated.nodeKey())
                || !identity(planned, planned.coldCandidate()).equals(generated.identity())) {
            throw new IllegalStateException(INCOMPLETE_CANDIDATES);
        }
        HierarchicalColdCandidate candidate = coldCandidate(generated);
        if (!jobs.storeColdCandidate(planned.job().jobId(), planned.execution(), candidate)) {
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
                planned.generationSettings().maxSummaryTokens());
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
                        && cold.estimatedTokenCount()
                                <= planned.generationSettings().maxSummaryTokens();
        if (!metadataMatches) {
            throw new IllegalStateException(INCOMPLETE_CANDIDATES);
        }
        generator.validateDurableCold(
                cold,
                descriptor.range(),
                identity(planned, descriptor),
                planned.generationSettings().maxSummaryTokens());
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
            PlannedCompaction planned,
            List<PlannedCandidate> requiredCandidates,
            Map<String, HierarchicalSummaryNode> available) {
        for (PlannedCandidate candidate : requiredCandidates) {
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
                planned.generationSettings().schemaVersion(),
                planned.generationSettings().promptVersion(),
                new ContextSummaryModelIdentity(
                        planned.generationSettings().modelProvider(),
                        planned.generationSettings().modelName()));
    }

    private void failJob(PlannedCompaction planned, RuntimeException failure) {
        String failureCode = HierarchicalCompactionException.FAILURE_CODE;
        jobs.transitionJob(
                planned.job().jobId(),
                HierarchicalCompactionJob.Status.GENERATING,
                HierarchicalCompactionJob.Status.FAILED,
                planned.execution(),
                failureCode);
        executions.fail(planned.execution(), failureCode, SAFE_FAILURE_MESSAGE);
    }

    private static ReadyCompaction ready(PlannedCompaction planned, boolean cancelled) {
        return new ReadyCompaction(planned, cancelled);
    }

    private static void requireCurrentJob(
            PlannedCompaction planned, HierarchicalCompactionJob current) {
        if (current == null
                || !current.jobId().equals(planned.job().jobId())
                || !current.conversationSessionId()
                        .equals(planned.execution().conversationSessionId())
                || !current.executionId().equals(planned.execution().executionId())
                || current.executionAttempt() != planned.execution().executionAttempt()
                || !current.ownerInstanceId().equals(planned.execution().ownerInstanceId())
                || current.inputUpToMessageSequence()
                        != planned.execution().inputUpToMessageSequence()) {
            throw new IllegalStateException(INVALID_JOB_STATE);
        }
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
            /** 包含 durable job、fence、冻结设置和范围元数据，不携带正文。 */
            PlannedCompaction planned,
            /** 生成期间是否观察到取消。 */
            boolean cancelled) {

        ReadyCompaction {
            Objects.requireNonNull(planned, "planned");
        }

        String jobId() {
            return planned.job().jobId();
        }

        HierarchicalCompactionPlan plan() {
            return planned.plan();
        }

        List<String> nodeIds() {
            return planned.candidates().stream().map(PlannedCandidate::nodeId).toList();
        }

        String coldCandidateId() {
            return planned.coldCandidate() == null ? null : planned.coldCandidate().nodeId();
        }
    }

    private static final class CancellationDetected extends RuntimeException {}
}
