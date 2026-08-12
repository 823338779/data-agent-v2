package com.datagent.agentv2.context.lsm.runtime;

import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan;
import com.datagent.agentv2.context.lsm.domain.LsmContextManifest;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmCompactionJobEntity;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmSummarySegmentEntity;
import com.datagent.agentv2.context.lsm.persistence.json.LsmDocumentCodec;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmCompactionJobRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmSummarySegmentRepository;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.CandidateInput;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.JobStatus;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.PlannedCompaction;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.ReadyCompaction;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator.GeneratedSummary;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryPrompt;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 按确定性 segment key 从首个缺失候选继续，支持 PLANNED/部分/READY 接管。 */
public class LsmCompactionRecoveryService {

    private static final String SEGMENT_ID_PREFIX = "context_summary_segment";
    private static final String SYSTEM_OPERATOR = "lsm-context";
    private static final String RECOVERABLE_STATUSES = "'PLANNED', 'GENERATING'";
    private static final String PLANNED_ID_PREFIX = "planned-";
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final int SUMMARY_SCHEMA_VERSION = 1;

    private final ContextSummaryGenerator generator;
    private final LsmSummarySegmentRepository segments;
    private final LsmCompactionJobRepository jobs;
    private final ContextExecutionControlPort executions;
    private final LsmDocumentCodec documents;
    private final IdGenerator ids;

    public LsmCompactionRecoveryService(
            ContextSummaryGenerator generator,
            LsmSummarySegmentRepository segments,
            LsmCompactionJobRepository jobs,
            ContextExecutionControlPort executions,
            LsmDocumentCodec documents,
            IdGenerator ids) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.segments = Objects.requireNonNull(segments, "segments");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    /** 事务外逐项查重/生成；每个成功候选立即以唯一 key 独立持久化。 */
    public ReadyCompaction resume(PlannedCompaction planned) {
        var execution = planned.request().execution();
        executions.assertOwnedAndWithinBudget(execution);
        LsmCompactionJobEntity job = requireJob(planned);
        if (executions.cancelIfRequested(execution)) {
            markCancelled(job, execution.ownerInstanceId());
            return new ReadyCompaction(planned, null, List.of(), true);
        }
        if (!Objects.equals(job.getOwnerInstanceId(), execution.ownerInstanceId())) {
            if (!jobs.claim(job, execution.ownerInstanceId())) {
                throw new IllegalStateException("LSM compaction job ownership was lost");
            }
            job = requireJob(planned);
        }
        if (job.getStatus().equals(JobStatus.PLANNED.name())) {
            if (!jobs.transition(
                    job,
                    execution.ownerInstanceId(),
                    "'PLANNED'",
                    JobStatus.GENERATING.name(),
                    null,
                    false)) {
                throw new IllegalStateException("LSM compaction GENERATING transition was lost");
            }
            job.setStatus(JobStatus.GENERATING.name());
        }

        Map<String, CandidateInput> inputs = new LinkedHashMap<>();
        planned.request().candidateInputs().forEach(input -> inputs.put(input.targetId(), input));
        Map<String, String> resolvedIds = new HashMap<>();
        List<LsmSummarySegment> candidates = new ArrayList<>();
        for (LsmCompactionPlan.PlannedOperation operation : planned.plan().operations()) {
            if (operation.targetId() == null) {
                continue;
            }
            CandidateInput input = inputs.get(operation.targetId());
            String segmentKey = generator.segmentKey(input.generationRequest());
            LsmSummarySegmentEntity persisted =
                    segments.findBySegmentKey(execution.conversationSessionId(), segmentKey)
                            .orElse(null);
            if (persisted == null) {
                // Resolve cascaded planner placeholders before the candidate crosses the DB
                // boundary.
                List<String> resolvedSourceIds =
                        resolveSourceIds(input.generationRequest().sourceIds(), resolvedIds);
                GeneratedSummary generated;
                try {
                    generated = generator.generate(input.generationRequest());
                } catch (RuntimeException summaryFailure) {
                    failSummary(job, execution);
                    throw new IllegalStateException("unreachable summary failure");
                }
                persisted =
                        persistCandidate(planned, operation, input, resolvedSourceIds, generated);
            }
            resolvedIds.put(operation.targetId(), persisted.getSummarySegmentId());
            candidates.add(toDomain(persisted));
            executions.assertOwnedAndWithinBudget(execution);
            if (executions.cancelIfRequested(execution)) {
                markCancelled(job, execution.ownerInstanceId());
                return new ReadyCompaction(planned, null, candidates, true);
            }
        }

        LsmCompactionPlan resolved = resolvePlan(planned.plan(), resolvedIds);
        if (job.getStatus().equals(JobStatus.PLANNED.name())
                || job.getStatus().equals(JobStatus.GENERATING.name())) {
            if (!jobs.transition(
                    job,
                    execution.ownerInstanceId(),
                    RECOVERABLE_STATUSES,
                    JobStatus.READY.name(),
                    null,
                    false)) {
                throw new IllegalStateException("LSM compaction READY transition was lost");
            }
        }
        return new ReadyCompaction(planned, resolved, candidates, false);
    }

    private void failSummary(
            LsmCompactionJobEntity job,
            com.datagent.agentv2.conversation.context.ContextExecutionRef execution) {
        executions.assertOwnedAndWithinBudget(execution);
        boolean failed =
                jobs.transition(
                        job,
                        execution.ownerInstanceId(),
                        "'PLANNED', 'GENERATING', 'READY'",
                        JobStatus.FAILED.name(),
                        LsmCompactionException.FAILURE_CODE,
                        true);
        if (!failed) {
            throw new IllegalStateException("LSM compaction failure transition was lost");
        }
        executions.fail(
                execution,
                LsmCompactionException.FAILURE_CODE,
                LsmCompactionException.summaryFailed().getMessage());
        throw LsmCompactionException.summaryFailed();
    }

    private void markCancelled(LsmCompactionJobEntity job, String ownerInstanceId) {
        if (!jobs.transition(
                job,
                ownerInstanceId,
                "'PLANNED', 'GENERATING', 'READY'",
                JobStatus.CANCELLED.name(),
                null,
                true)) {
            throw new IllegalStateException("LSM compaction cancellation transition was lost");
        }
    }

    private LsmSummarySegmentEntity persistCandidate(
            PlannedCompaction planned,
            LsmCompactionPlan.PlannedOperation operation,
            CandidateInput input,
            List<String> resolvedSourceIds,
            GeneratedSummary generated) {
        LocalDateTime now = LocalDateTime.now();
        LsmSummarySegmentEntity entity = new LsmSummarySegmentEntity();
        entity.setSummarySegmentId(ids.next(SEGMENT_ID_PREFIX));
        entity.setConversationSessionId(planned.request().execution().conversationSessionId());
        entity.setSegmentKey(generated.segmentKey());
        entity.setKind(operation.targetKind().name());
        entity.setLevel(operation.targetLevel());
        entity.setStartTurnSequence(operation.coverage().startTurnSequence());
        entity.setEndTurnSequence(operation.coverage().endTurnSequence());
        entity.setStartMessageSequence(
                input.generationRequest().messageCoverage().startMessageSequence());
        entity.setEndMessageSequence(
                input.generationRequest().messageCoverage().endMessageSequence());
        entity.setPartialSegment(operation.partialSegment());
        entity.setSummaryDocument(generated.serializedJson());
        entity.setEstimatedTokenCount(generated.estimatedTokenCount());
        entity.setSourceSegmentIds(documents.writeStringList(resolvedSourceIds));
        entity.setSourceContentSha256(sha256(input.generationRequest().sourceDocument()));
        entity.setSummarySchemaVersion(SUMMARY_SCHEMA_VERSION);
        entity.setPromptVersion(ContextSummaryPrompt.PROMPT_VERSION);
        entity.setModelProvider(generated.provider());
        entity.setModelName(generated.model());
        entity.setCreationPolicy(documents.writePolicy(planned.request().policySnapshot()));
        entity.setSourceSegmentCount(resolvedSourceIds.size());
        entity.setCreatedAt(now);
        entity.setCreatedBy(SYSTEM_OPERATOR);
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(SYSTEM_OPERATOR);
        entity.setDeleted(false);
        entity.setVersion(0L);
        return segments.findOrCreate(entity);
    }

    private static List<String> resolveSourceIds(
            List<String> sourceIds, Map<String, String> resolvedIds) {
        return sourceIds.stream()
                .map(
                        sourceId -> {
                            String resolvedId = resolvedIds.get(sourceId);
                            if (resolvedId == null && sourceId.startsWith(PLANNED_ID_PREFIX)) {
                                throw new IllegalStateException(
                                        "Cascaded summary source has not been resolved");
                            }
                            return resolvedId == null ? sourceId : resolvedId;
                        })
                .toList();
    }

    private LsmCompactionJobEntity requireJob(PlannedCompaction planned) {
        var execution = planned.request().execution();
        return jobs.findBoundaryJob(
                        execution.executionId(),
                        execution.executionAttempt(),
                        execution.inputUpToMessageSequence())
                .filter(job -> job.getCompactionJobId().equals(planned.jobId()))
                .orElseThrow(() -> new IllegalStateException("LSM compaction job is missing"));
    }

    private static LsmCompactionPlan resolvePlan(
            LsmCompactionPlan plan, Map<String, String> resolvedIds) {
        List<LsmCompactionPlan.PlannedOperation> operations =
                plan.operations().stream()
                        .map(
                                operation ->
                                        new LsmCompactionPlan.PlannedOperation(
                                                operation.type(),
                                                resolve(operation.targetId(), resolvedIds),
                                                operation.sourceIds().stream()
                                                        .map(id -> resolve(id, resolvedIds))
                                                        .toList(),
                                                operation.targetKind(),
                                                operation.targetLevel(),
                                                operation.coverage(),
                                                operation.partialSegment(),
                                                operation.estimatedTokenCount()))
                        .toList();
        List<LsmSummarySegment> projected =
                plan.projectedSegments().stream()
                        .map(
                                segment ->
                                        new LsmSummarySegment(
                                                resolve(segment.segmentId(), resolvedIds),
                                                segment.kind(),
                                                segment.level(),
                                                segment.coverage(),
                                                segment.estimatedTokenCount(),
                                                segment.sourceSegmentIds().stream()
                                                        .map(id -> resolve(id, resolvedIds))
                                                        .toList(),
                                                segment.partialSegment()))
                        .toList();
        LsmContextManifest manifest = plan.proposedManifest();
        LsmContextManifest resolvedManifest =
                new LsmContextManifest(
                        resolve(manifest.activeRootSegmentId(), resolvedIds),
                        manifest.activeSegmentIds().stream()
                                .map(id -> resolve(id, resolvedIds))
                                .toList(),
                        manifest.pendingRawStartTurnSequence(),
                        manifest.pendingRawEndTurnSequence(),
                        manifest.warmStartTurnSequence(),
                        manifest.warmEndTurnSequence(),
                        manifest.generation(),
                        manifest.effectiveFanout(),
                        manifest.effectiveMaxLevel());
        return new LsmCompactionPlan(plan.hotBoundary(), operations, resolvedManifest, projected);
    }

    private static String resolve(String id, Map<String, String> resolvedIds) {
        return id == null ? null : resolvedIds.getOrDefault(id, id);
    }

    private static LsmSummarySegment toDomain(LsmSummarySegmentEntity entity) {
        return new LsmSummarySegment(
                entity.getSummarySegmentId(),
                LsmSummarySegment.SegmentKind.valueOf(entity.getKind()),
                entity.getLevel(),
                new LsmSummarySegment.Coverage(
                        entity.getStartTurnSequence(), entity.getEndTurnSequence()),
                entity.getEstimatedTokenCount(),
                List.of(),
                entity.isPartialSegment());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance(HASH_ALGORITHM)
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
