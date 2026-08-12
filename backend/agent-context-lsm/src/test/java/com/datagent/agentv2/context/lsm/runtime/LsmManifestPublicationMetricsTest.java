package com.datagent.agentv2.context.lsm.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan;
import com.datagent.agentv2.context.lsm.domain.LsmContextManifest;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment;
import com.datagent.agentv2.context.lsm.observability.LsmContextMetrics;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmCompactionJobEntity;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmContextManifestEntity;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmSummarySegmentEntity;
import com.datagent.agentv2.context.lsm.persistence.json.LsmDocumentCodec;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmCompactionJobRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmContextManifestRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmSummarySegmentRepository;
import com.datagent.agentv2.context.lsm.planning.LsmInvariantValidator;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.CompactionRequest;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.JobStatus;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.PlannedCompaction;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.PublishResult;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.ReadyCompaction;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextPolicySnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LsmManifestPublicationMetricsTest {

    @Test
    void recordsOnlyTheManifestCasFailureAsAConflict() {
        LsmContextManifestRepository manifests = mock(LsmContextManifestRepository.class);
        LsmSummarySegmentRepository segments = mock(LsmSummarySegmentRepository.class);
        LsmCompactionJobRepository jobs = mock(LsmCompactionJobRepository.class);
        ContextExecutionControlPort executions = mock(ContextExecutionControlPort.class);
        LsmInvariantValidator invariants = mock(LsmInvariantValidator.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LsmManifestPublicationTransaction publication =
                new LsmManifestPublicationTransaction(
                        manifests,
                        segments,
                        jobs,
                        executions,
                        invariants,
                        new LsmDocumentCodec(),
                        new LsmContextMetrics(registry));
        ReadyCompaction ready = ready();
        LsmCompactionJobEntity job = job();
        LsmContextManifestEntity current = new LsmContextManifestEntity();
        current.setGeneration(7L);
        current.setVersion(3L);
        when(jobs.findBoundaryJob("execution", 1L, 2L)).thenReturn(Optional.of(job));
        when(manifests.findManifest("session")).thenReturn(Optional.of(current));
        when(segments.findByIdsInOrder(List.of("segment"))).thenReturn(List.of(entity()));
        when(manifests.compareAndSetManifest(
                        anyString(),
                        anyLong(),
                        anyLong(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyLong(),
                        any()))
                .thenReturn(false);
        when(jobs.transition(any(), anyString(), anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(true);

        PublishResult result = publication.publish(ready);

        assertThat(result).isEqualTo(PublishResult.SUPERSEDED);
        assertThat(registry.get("agent_context_manifest_cas_conflicts_total").counter().count())
                .isEqualTo(1d);
    }

    private static ReadyCompaction ready() {
        ContextExecutionRef execution =
                new ContextExecutionRef("tenant", "session", "execution", "owner", 1L, 2L);
        CompactionRequest request = mock(CompactionRequest.class);
        when(request.execution()).thenReturn(execution);
        when(request.policySnapshot())
                .thenReturn(new ContextPolicySnapshot(1, 5, 4, 10, 75, 65, 100, 109, 10));
        PlannedCompaction planned =
                new PlannedCompaction("job", request, plan(), 7L, 3L, JobStatus.READY, false);
        return new ReadyCompaction(planned, plan(), List.of(segment()), false);
    }

    private static LsmCompactionPlan plan() {
        LsmSummarySegment segment = segment();
        return new LsmCompactionPlan(
                new LsmCompactionPlan.HotBoundary(null, null, 0, null),
                List.of(),
                new LsmContextManifest(null, List.of("segment"), null, null, null, null, 8L, 5, 4),
                List.of(segment));
    }

    private static LsmSummarySegment segment() {
        return new LsmSummarySegment(
                "segment",
                LsmSummarySegment.SegmentKind.STANDARD,
                0,
                new LsmSummarySegment.Coverage(1, 1),
                10,
                List.of("raw-1"),
                false);
    }

    private static LsmSummarySegmentEntity entity() {
        LsmSummarySegmentEntity entity = new LsmSummarySegmentEntity();
        entity.setSummarySegmentId("segment");
        entity.setKind(LsmSummarySegment.SegmentKind.STANDARD.name());
        entity.setLevel(0);
        entity.setStartTurnSequence(1L);
        entity.setEndTurnSequence(1L);
        return entity;
    }

    private static LsmCompactionJobEntity job() {
        LsmCompactionJobEntity job = new LsmCompactionJobEntity();
        job.setCompactionJobId("job");
        job.setOwnerInstanceId("owner");
        job.setStatus(JobStatus.READY.name());
        return job;
    }
}
