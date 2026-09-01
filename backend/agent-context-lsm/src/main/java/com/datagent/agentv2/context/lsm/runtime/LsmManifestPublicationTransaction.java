package com.datagent.agentv2.context.lsm.runtime;

import com.datagent.agentv2.context.lsm.observability.LsmContextMetrics;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmContextManifestEntity;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmSummarySegmentEntity;
import com.datagent.agentv2.context.lsm.persistence.json.LsmDocumentCodec;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmContextManifestRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmSummarySegmentRepository;
import com.datagent.agentv2.context.lsm.planning.LsmInvariantValidator;
import com.datagent.agentv2.context.lsm.runtime.LsmCandidateGenerator.GeneratedCompaction;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.CompactionRequest;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.PublishResult;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 以 manifest version/generation CAS 原子发布本次调用生成的摘要视图。 */
@RequiredArgsConstructor
public class LsmManifestPublicationTransaction {

    private final LsmContextManifestRepository manifests;
    private final LsmSummarySegmentRepository segments;
    private final ContextExecutionControlPort executions;
    private final LsmInvariantValidator invariants;
    private final LsmDocumentCodec documents;
    private final LsmContextMetrics metrics;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PublishResult publish(CompactionRequest request, GeneratedCompaction generated) {
        executions.assertRunningAndWithinDeadline(request.execution());
        LsmContextManifestEntity current =
                manifests
                        .findManifest(request.execution().conversationSessionId())
                        .orElseThrow(() -> new IllegalStateException("LSM manifest is missing"));
        if (current.getGeneration() != request.planningInput().manifest().generation()) {
            return PublishResult.SUPERSEDED;
        }
        invariants.validatePlan(request.planningInput(), generated.plan());
        List<LsmSummarySegmentEntity> active =
                segments.findByIdsInOrder(generated.plan().proposedManifest().activeSegmentIds());
        if (active.size() != generated.plan().proposedManifest().activeSegmentIds().size()) {
            throw new IllegalStateException("published manifest references missing segments");
        }
        LsmContextManifestEntity replacement = replacement(request, generated, current);
        boolean published =
                manifests.compareAndSetManifest(
                        request.execution().conversationSessionId(),
                        current.getVersion(),
                        current.getGeneration(),
                        replacement);
        if (!published) {
            metrics.recordManifestCasConflict();
            return PublishResult.SUPERSEDED;
        }
        return PublishResult.PUBLISHED;
    }

    private LsmContextManifestEntity replacement(
            CompactionRequest request,
            GeneratedCompaction generated,
            LsmContextManifestEntity current) {
        var manifest = generated.plan().proposedManifest();
        var hot = generated.plan().hotBoundary();
        var policy = request.policySnapshot();
        LsmContextManifestEntity replacement = new LsmContextManifestEntity();
        replacement.setActiveRootSegmentId(manifest.activeRootSegmentId());
        replacement.setActiveSegmentIds(documents.writeStringList(manifest.activeSegmentIds()));
        replacement.setPendingRawStartTurnSequence(manifest.pendingRawStartTurnSequence());
        replacement.setPendingRawEndTurnSequence(manifest.pendingRawEndTurnSequence());
        replacement.setHotFromTurnSequence(
                hot.startTurnSequence() == null ? 0L : hot.startTurnSequence());
        replacement.setEvaluatedUpToMessageSequence(request.execution().inputUpToMessageSequence());
        replacement.setEffectivePolicyVersion(policy.policyVersion());
        replacement.setEffectiveFanout(policy.fanout());
        replacement.setEffectiveMaxLevel(policy.maxLevel());
        replacement.setGeneration(current.getGeneration() + 1L);
        replacement.setUpdatedAt(LocalDateTime.now());
        replacement.setUpdatedBy("lsm-context");
        return replacement;
    }
}
