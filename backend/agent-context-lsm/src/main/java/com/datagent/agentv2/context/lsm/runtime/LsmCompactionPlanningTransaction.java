package com.datagent.agentv2.context.lsm.runtime;

import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmCompactionJobEntity;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmContextManifestEntity;
import com.datagent.agentv2.context.lsm.persistence.json.LsmDocumentCodec;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmCompactionJobRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmContextManifestRepository;
import com.datagent.agentv2.context.lsm.planning.LsmCompactionPlanner;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.CandidateInput;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.CompactionRequest;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.JobStatus;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.PlannedCompaction;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort.ContextLifecycleEvent;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 在独立短事务中固定 manifest、计划、输入边界和 owner。 */
public class LsmCompactionPlanningTransaction {

    private static final String JOB_ID_PREFIX = "context_compaction_job";
    private static final String SYSTEM_OPERATOR = "lsm-context";
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String EVENT_JOB_ID = "compactionJobId";
    private static final String EVENT_INPUT_BOUNDARY = "inputUpToMessageSequence";

    private final LsmCompactionPlanner planner;
    private final ContextSummaryGenerator generator;
    private final LsmCompactionJobRepository jobs;
    private final LsmContextManifestRepository manifests;
    private final LsmDocumentCodec documents;
    private final IdGenerator ids;
    private final ContextExecutionControlPort executions;

    public LsmCompactionPlanningTransaction(
            LsmCompactionPlanner planner,
            ContextSummaryGenerator generator,
            LsmCompactionJobRepository jobs,
            LsmContextManifestRepository manifests,
            LsmDocumentCodec documents,
            IdGenerator ids,
            ContextExecutionControlPort executions) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.generator = Objects.requireNonNull(generator, "generator");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.manifests = Objects.requireNonNull(manifests, "manifests");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.executions = Objects.requireNonNull(executions, "executions");
    }

    /** execution fencing 与 job PLANNED 写入在同一 REQUIRES_NEW 事务完成。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PlannedCompaction begin(CompactionRequest request) {
        executions.assertOwnedAndWithinBudget(request.execution());
        LsmCompactionPlan plan = planner.plan(request.planningInput());
        if (plan.operations().isEmpty()) {
            return PlannedCompaction.noop(request);
        }
        validateCandidateMapping(request, plan);

        // 当前 manifest 必须仍与 planner 输入相同，防止持久化一份已经过期的计划。
        LsmContextManifestEntity source =
                manifests
                        .findManifest(request.execution().conversationSessionId())
                        .orElseThrow(() -> new IllegalStateException("LSM manifest is missing"));
        if (source.getGeneration() != request.planningInput().manifest().generation()) {
            return PlannedCompaction.superseded(request, plan);
        }

        Map<String, String> segmentKeys = new LinkedHashMap<>();
        Map<String, String> sourceContentHashes = new LinkedHashMap<>();
        for (CandidateInput candidate : request.candidateInputs()) {
            segmentKeys.put(
                    candidate.targetId(), generator.segmentKey(candidate.generationRequest()));
            sourceContentHashes.put(
                    candidate.targetId(), sha256(candidate.generationRequest().sourceDocument()));
        }
        String planDocument = documents.writeCompactionPlan(plan, segmentKeys, sourceContentHashes);
        LsmCompactionJobEntity created = newJob(request, source, planDocument);
        LsmCompactionJobEntity persisted = jobs.findOrCreate(created);
        JobStatus persistedStatus = JobStatus.valueOf(persisted.getStatus());
        if (persistedStatus == JobStatus.PUBLISHED) {
            return planned(persisted, request, plan, JobStatus.PUBLISHED);
        }
        if (persistedStatus == JobStatus.FAILED) {
            throw LsmCompactionException.summaryFailed();
        }
        if (persistedStatus == JobStatus.CANCELLED) {
            return planned(persisted, request, plan, JobStatus.CANCELLED);
        }
        if (persistedStatus == JobStatus.SUPERSEDED) {
            if (!jobs.replanSuperseded(persisted, created)) {
                throw new IllegalStateException("LSM superseded job replan was lost");
            }
            persisted =
                    jobs.findRecoverableJob(
                                    request.execution().executionId(),
                                    request.execution().executionAttempt(),
                                    request.execution().inputUpToMessageSequence())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "LSM replanned job is missing"));
        }
        if (!Objects.equals(
                persisted.getOwnerInstanceId(), request.execution().ownerInstanceId())) {
            if (!jobs.claim(persisted, request.execution().ownerInstanceId())) {
                return PlannedCompaction.noop(request);
            }
            persisted =
                    jobs.findRecoverableJob(
                                    request.execution().executionId(),
                                    request.execution().executionAttempt(),
                                    request.execution().inputUpToMessageSequence())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "LSM compaction job claim was lost"));
        }
        if (!Objects.equals(persisted.getPlanSha256(), created.getPlanSha256())
                || persisted.getSourceManifestGeneration() != source.getGeneration()
                || persisted.getSourceManifestVersion() != source.getVersion()) {
            jobs.transition(
                    persisted,
                    request.execution().ownerInstanceId(),
                    "'PLANNED', 'GENERATING', 'READY'",
                    JobStatus.SUPERSEDED.name(),
                    null,
                    true);
            return planned(persisted, request, plan, JobStatus.SUPERSEDED);
        }
        if (executions.cancelIfRequested(request.execution())) {
            jobs.transition(
                    persisted,
                    request.execution().ownerInstanceId(),
                    "'PLANNED', 'GENERATING', 'READY'",
                    JobStatus.CANCELLED.name(),
                    null,
                    true);
            return PlannedCompaction.cancelled(
                    persisted.getCompactionJobId(),
                    request,
                    plan,
                    persisted.getSourceManifestGeneration(),
                    persisted.getSourceManifestVersion());
        }
        executions.publish(
                request.execution(),
                new ContextLifecycleEvent(
                        ContextLifecycleEvent.Type.STARTED,
                        Map.of(
                                EVENT_JOB_ID,
                                persisted.getCompactionJobId(),
                                EVENT_INPUT_BOUNDARY,
                                request.execution().inputUpToMessageSequence())));
        return new PlannedCompaction(
                persisted.getCompactionJobId(),
                request,
                plan,
                persisted.getSourceManifestGeneration(),
                persisted.getSourceManifestVersion(),
                JobStatus.valueOf(persisted.getStatus()),
                false);
    }

    private static PlannedCompaction planned(
            LsmCompactionJobEntity job,
            CompactionRequest request,
            LsmCompactionPlan plan,
            JobStatus status) {
        return new PlannedCompaction(
                job.getCompactionJobId(),
                request,
                plan,
                job.getSourceManifestGeneration(),
                job.getSourceManifestVersion(),
                status,
                false);
    }

    private static void validateCandidateMapping(
            CompactionRequest request, LsmCompactionPlan plan) {
        Set<String> targets =
                plan.operations().stream()
                        .filter(operation -> operation.targetId() != null)
                        .map(LsmCompactionPlan.PlannedOperation::targetId)
                        .collect(Collectors.toSet());
        Set<String> supplied =
                request.candidateInputs().stream()
                        .map(CandidateInput::targetId)
                        .collect(Collectors.toSet());
        if (!targets.equals(supplied)) {
            throw new IllegalArgumentException("candidate inputs must match plan targets exactly");
        }
    }

    private LsmCompactionJobEntity newJob(
            CompactionRequest request, LsmContextManifestEntity source, String planDocument) {
        LocalDateTime now = LocalDateTime.now();
        LsmCompactionJobEntity job = new LsmCompactionJobEntity();
        job.setCompactionJobId(ids.next(JOB_ID_PREFIX));
        job.setConversationSessionId(request.execution().conversationSessionId());
        job.setExecutionId(request.execution().executionId());
        job.setExecutionAttempt(request.execution().executionAttempt());
        job.setInputUpToMessageSequence(request.execution().inputUpToMessageSequence());
        job.setSourceManifestGeneration(source.getGeneration());
        job.setSourceManifestVersion(source.getVersion());
        job.setPolicySnapshot(documents.writePolicy(request.policySnapshot()));
        job.setPlanDocument(planDocument);
        job.setPlanSha256(sha256(planDocument));
        job.setStatus(JobStatus.PLANNED.name());
        job.setOwnerInstanceId(request.execution().ownerInstanceId());
        job.setCreatedAt(now);
        job.setCreatedBy(SYSTEM_OPERATOR);
        job.setUpdatedAt(now);
        job.setUpdatedBy(SYSTEM_OPERATOR);
        job.setDeleted(false);
        job.setVersion(0L);
        return job;
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
