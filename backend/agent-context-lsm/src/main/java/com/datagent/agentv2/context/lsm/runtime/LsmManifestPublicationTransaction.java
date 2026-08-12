package com.datagent.agentv2.context.lsm.runtime;

import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan;
import com.datagent.agentv2.context.lsm.observability.LsmContextMetrics;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmCompactionJobEntity;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmContextManifestEntity;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmSummarySegmentEntity;
import com.datagent.agentv2.context.lsm.persistence.json.LsmDocumentCodec;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmCompactionJobRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmContextManifestRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmSummarySegmentRepository;
import com.datagent.agentv2.context.lsm.planning.LsmInvariantValidator;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.JobStatus;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.PublishResult;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.ReadyCompaction;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort.ContextLifecycleEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 在独立短事务中重新 fencing 并通过 manifest CAS 原子发布候选视图。 */
public class LsmManifestPublicationTransaction {

    private static final String SYSTEM_OPERATOR = "lsm-context";
    private static final String READY_STATUS = "'READY'";
    private static final String EVENT_JOB_ID = "compactionJobId";
    private static final String EVENT_GENERATION = "manifestGeneration";

    private final LsmContextManifestRepository manifests;
    private final LsmSummarySegmentRepository segments;
    private final LsmCompactionJobRepository jobs;
    private final ContextExecutionControlPort executions;
    private final LsmInvariantValidator invariants;
    private final LsmDocumentCodec documents;
    private final LsmContextMetrics metrics;

    public LsmManifestPublicationTransaction(
            LsmContextManifestRepository manifests,
            LsmSummarySegmentRepository segments,
            LsmCompactionJobRepository jobs,
            ContextExecutionControlPort executions,
            LsmInvariantValidator invariants,
            LsmDocumentCodec documents) {
        this(
                manifests,
                segments,
                jobs,
                executions,
                invariants,
                documents,
                LsmContextMetrics.isolated());
    }

    public LsmManifestPublicationTransaction(
            LsmContextManifestRepository manifests,
            LsmSummarySegmentRepository segments,
            LsmCompactionJobRepository jobs,
            ContextExecutionControlPort executions,
            LsmInvariantValidator invariants,
            LsmDocumentCodec documents,
            LsmContextMetrics metrics) {
        this.manifests = Objects.requireNonNull(manifests, "manifests");
        this.segments = Objects.requireNonNull(segments, "segments");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.invariants = Objects.requireNonNull(invariants, "invariants");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** owner/attempt/cancel、source manifest、不变量和 CAS 在同一事务内验证。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PublishResult publish(ReadyCompaction ready) {
        var planned = ready.planned();
        var execution = planned.request().execution();
        LsmCompactionJobEntity job = requireJob(ready);
        if (!job.getOwnerInstanceId().equals(execution.ownerInstanceId())) {
            return PublishResult.SUPERSEDED;
        }
        executions.assertOwnedAndWithinBudget(execution);
        if (job.getStatus().equals(JobStatus.PUBLISHED.name())) {
            long currentGeneration =
                    manifests
                            .findManifest(execution.conversationSessionId())
                            .map(LsmContextManifestEntity::getGeneration)
                            .orElse(-1L);
            return currentGeneration == ready.plan().proposedManifest().generation()
                    ? PublishResult.PUBLISHED
                    : PublishResult.SUPERSEDED;
        }
        if (!job.getStatus().equals(JobStatus.READY.name())) {
            return PublishResult.SUPERSEDED;
        }
        if (executions.cancelIfRequested(execution)) {
            if (!jobs.transition(
                    job,
                    execution.ownerInstanceId(),
                    READY_STATUS,
                    JobStatus.CANCELLED.name(),
                    null,
                    true)) {
                throw new IllegalStateException("LSM publication cancellation was lost");
            }
            return PublishResult.CANCELLED;
        }

        LsmContextManifestEntity current =
                manifests
                        .findManifest(execution.conversationSessionId())
                        .orElseThrow(() -> new IllegalStateException("LSM manifest is missing"));
        if (current.getGeneration() != planned.sourceManifestGeneration()
                || current.getVersion() != planned.sourceManifestVersion()) {
            markSuperseded(job, execution.ownerInstanceId());
            return PublishResult.SUPERSEDED;
        }

        // 发布前同时验证 planner 领域不变量和全部活动 segment 的真实覆盖、层级及存在性。
        invariants.validatePlan(planned.request().planningInput(), ready.plan());
        List<LsmSummarySegmentEntity> active =
                segments.findByIdsInOrder(ready.plan().proposedManifest().activeSegmentIds());
        if (active.size() != ready.plan().proposedManifest().activeSegmentIds().size()) {
            throw new IllegalStateException("published manifest references missing segments");
        }
        assertPersistedSegmentsMatch(ready.plan(), active);

        LsmContextManifestEntity replacement = replacement(ready, current);
        boolean published =
                manifests.compareAndSetManifest(
                        execution.conversationSessionId(),
                        planned.sourceManifestVersion(),
                        planned.sourceManifestGeneration(),
                        planned.jobId(),
                        execution.ownerInstanceId(),
                        execution.executionAttempt(),
                        execution.inputUpToMessageSequence(),
                        replacement);
        if (!published) {
            metrics.recordManifestCasConflict();
            markSuperseded(job, execution.ownerInstanceId());
            return PublishResult.SUPERSEDED;
        }
        if (!jobs.transition(
                job,
                execution.ownerInstanceId(),
                READY_STATUS,
                JobStatus.PUBLISHED.name(),
                null,
                true)) {
            throw new IllegalStateException("LSM published job transition was lost");
        }
        executions.publish(
                execution,
                new ContextLifecycleEvent(
                        ContextLifecycleEvent.Type.COMPLETED,
                        Map.of(
                                EVENT_JOB_ID,
                                planned.jobId(),
                                EVENT_GENERATION,
                                replacement.getGeneration())));
        return PublishResult.PUBLISHED;
    }

    private LsmCompactionJobEntity requireJob(ReadyCompaction ready) {
        var execution = ready.planned().request().execution();
        return jobs.findBoundaryJob(
                        execution.executionId(),
                        execution.executionAttempt(),
                        execution.inputUpToMessageSequence())
                .filter(job -> job.getCompactionJobId().equals(ready.planned().jobId()))
                .orElseThrow(() -> new IllegalStateException("LSM READY job fencing failed"));
    }

    private void markSuperseded(LsmCompactionJobEntity job, String ownerInstanceId) {
        jobs.transition(
                job, ownerInstanceId, READY_STATUS, JobStatus.SUPERSEDED.name(), null, true);
    }

    private static void assertPersistedSegmentsMatch(
            LsmCompactionPlan plan, List<LsmSummarySegmentEntity> persisted) {
        Map<String, com.datagent.agentv2.context.lsm.domain.LsmSummarySegment> projected =
                plan.projectedSegments().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        com.datagent.agentv2.context.lsm.domain.LsmSummarySegment
                                                ::segmentId,
                                        segment -> segment));
        for (LsmSummarySegmentEntity entity : persisted) {
            var expected = projected.get(entity.getSummarySegmentId());
            if (expected == null
                    || entity.getLevel() != expected.level()
                    || !entity.getKind().equals(expected.kind().name())
                    || entity.getStartTurnSequence() != expected.coverage().startTurnSequence()
                    || entity.getEndTurnSequence() != expected.coverage().endTurnSequence()) {
                throw new IllegalStateException("persisted segment does not match published plan");
            }
        }
    }

    private LsmContextManifestEntity replacement(
            ReadyCompaction ready, LsmContextManifestEntity current) {
        var manifest = ready.plan().proposedManifest();
        var policy = ready.planned().request().policySnapshot();
        var hot = ready.plan().hotBoundary();
        LsmContextManifestEntity replacement = new LsmContextManifestEntity();
        replacement.setActiveRootSegmentId(manifest.activeRootSegmentId());
        replacement.setActiveSegmentIds(documents.writeStringList(manifest.activeSegmentIds()));
        replacement.setPendingRawStartTurnSequence(manifest.pendingRawStartTurnSequence());
        replacement.setPendingRawEndTurnSequence(manifest.pendingRawEndTurnSequence());
        replacement.setWarmCutoffTurnSequence(
                manifest.warmEndTurnSequence() == null ? 0L : manifest.warmEndTurnSequence());
        replacement.setHotFromTurnSequence(
                hot.startTurnSequence() == null ? 0L : hot.startTurnSequence());
        replacement.setEvaluatedUpToMessageSequence(
                ready.planned().request().execution().inputUpToMessageSequence());
        replacement.setEffectivePolicyVersion(policy.policyVersion());
        replacement.setEffectiveFanout(policy.fanout());
        replacement.setEffectiveMaxLevel(policy.maxLevel());
        replacement.setGeneration(current.getGeneration() + 1L);
        replacement.setUpdatedAt(LocalDateTime.now());
        replacement.setUpdatedBy(SYSTEM_OPERATOR);
        return replacement;
    }
}
