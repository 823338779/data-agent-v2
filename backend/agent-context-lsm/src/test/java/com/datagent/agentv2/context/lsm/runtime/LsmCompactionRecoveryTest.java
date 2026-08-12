package com.datagent.agentv2.context.lsm.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan;
import com.datagent.agentv2.context.lsm.domain.LsmContextManifest;
import com.datagent.agentv2.context.lsm.domain.LsmPlanningInput;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmCompactionJobEntity;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmSummarySegmentEntity;
import com.datagent.agentv2.context.lsm.persistence.json.LsmDocumentCodec;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmCompactionJobRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmSummarySegmentRepository;
import com.datagent.agentv2.context.lsm.planning.ConversationTokenEstimator;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.CandidateInput;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.CompactionRequest;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.JobStatus;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.PlannedCompaction;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.ReadyCompaction;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryDocumentValidator;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator.GenerationRequest;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator.MessageCoverage;
import com.datagent.agentv2.context.lsm.summary.LsmSegmentKeyFactory;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextPolicySnapshot;
import com.datagent.agentv2.conversation.context.ContextSummaryModelPort;
import com.datagent.agentv2.conversation.context.ContextSummaryResult;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 验证 PLANNED、部分候选、READY、取消和失败的事务外恢复。 */
class LsmCompactionRecoveryTest {

    private static final String TENANT_ID = "tenant_recovery";
    private static final String SESSION_ID = "session_recovery";
    private static final String EXECUTION_ID = "execution_recovery";
    private static final String OWNER_ID = "owner_recovery";
    private static final String JOB_ID = "job_recovery";
    private static final String TARGET_ONE = "target-one";
    private static final String TARGET_TWO = "target-two";
    private static final String SOURCE_DOCUMENT = "{\"messages\":[]}";
    private static final String PROVIDER = "test-provider";
    private static final String MODEL = "test-model";

    private final ContextSummaryModelPort model = mock(ContextSummaryModelPort.class);
    private final LsmSummarySegmentRepository segments = mock(LsmSummarySegmentRepository.class);
    private final LsmCompactionJobRepository jobs = mock(LsmCompactionJobRepository.class);
    private final ContextExecutionControlPort executions = mock(ContextExecutionControlPort.class);
    private final LsmDocumentCodec documents = new LsmDocumentCodec();
    private final Map<String, LsmSummarySegmentEntity> stored = new HashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final ContextSummaryGenerator generator =
            new ContextSummaryGenerator(
                    model,
                    new ContextSummaryDocumentValidator(),
                    new ConversationTokenEstimator(),
                    new LsmSegmentKeyFactory());

    private LsmCompactionJobEntity job;
    private LsmCompactionRecoveryService recovery;

    @BeforeEach
    void setUp() {
        job = job(JobStatus.PLANNED);
        IdGenerator idGenerator = prefix -> prefix + "-" + ids.incrementAndGet();
        recovery =
                new LsmCompactionRecoveryService(
                        generator, segments, jobs, executions, documents, idGenerator);
        when(jobs.findBoundaryJob(EXECUTION_ID, 1L, 20L)).thenAnswer(call -> Optional.of(job));
        when(jobs.transition(
                        any(), anyString(), anyString(), anyString(), any(), any(Boolean.class)))
                .thenAnswer(
                        call -> {
                            job.setStatus(call.getArgument(3));
                            return true;
                        });
        when(segments.findBySegmentKey(SESSION_ID, keyOne()))
                .thenAnswer(call -> Optional.ofNullable(stored.get(keyOne())));
        when(segments.findBySegmentKey(SESSION_ID, keyTwo()))
                .thenAnswer(call -> Optional.ofNullable(stored.get(keyTwo())));
        when(segments.findOrCreate(any()))
                .thenAnswer(
                        call -> {
                            LsmSummarySegmentEntity entity = call.getArgument(0);
                            stored.putIfAbsent(entity.getSegmentKey(), entity);
                            return stored.get(entity.getSegmentKey());
                        });
    }

    @Test
    void crashAfterPlannedResumesAllCandidatesAndBecomesReady() {
        when(model.summarize(any()))
                .thenReturn(result(validSummary(1, 1, 1, 10)), result(validSummary(2, 2, 11, 20)));

        ReadyCompaction ready = recovery.resume(planned());

        assertThat(ready.candidates()).hasSize(2);
        assertThat(stored).hasSize(2);
        assertThat(job.getStatus()).isEqualTo(JobStatus.READY.name());
    }

    @Test
    void crashAfterOneCandidateReusesExactKeyAndContinuesFromFirstMissing() {
        when(model.summarize(any()))
                .thenReturn(result(validSummary(1, 1, 1, 10)))
                .thenThrow(new AssertionError("simulated process crash"))
                .thenReturn(result(validSummary(2, 2, 11, 20)));

        assertThatThrownBy(() -> recovery.resume(planned())).isInstanceOf(AssertionError.class);
        assertThat(stored).hasSize(1);

        ReadyCompaction ready = recovery.resume(planned());

        assertThat(ready.candidates()).hasSize(2);
        assertThat(stored).hasSize(2);
        verify(model, times(3)).summarize(any());
    }

    @Test
    void readyRecoveryReusesBothCandidatesWithoutCallingModel() {
        stored.put(keyOne(), entity(TARGET_ONE, keyOne(), 1, 1));
        stored.put(keyTwo(), entity(TARGET_TWO, keyTwo(), 2, 2));
        job.setStatus(JobStatus.READY.name());

        ReadyCompaction ready = recovery.resume(planned());

        assertThat(ready.candidates()).hasSize(2);
        verify(model, never()).summarize(any());
    }

    @Test
    void cancellationMarksJobAndDoesNotGenerate() {
        when(executions.cancelIfRequested(any())).thenReturn(true);

        ReadyCompaction ready = recovery.resume(planned());

        assertThat(ready.cancelled()).isTrue();
        assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED.name());
        verify(model, never()).summarize(any());
    }

    @Test
    void twoProviderFailuresMarkFailedWithStableCode() {
        when(model.summarize(any())).thenThrow(new IllegalStateException("provider body secret"));

        assertThatThrownBy(() -> recovery.resume(planned()))
                .isInstanceOf(LsmCompactionException.class)
                .hasMessage("Context compaction failed")
                .hasNoCause();
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED.name());
        verify(model, times(2)).summarize(any());
        verify(executions)
                .fail(
                        any(),
                        org.mockito.ArgumentMatchers.eq("CONTEXT_COMPACTION_FAILED"),
                        org.mockito.ArgumentMatchers.eq("Context compaction failed"));
    }

    @Test
    void ownershipLossIsNotMisreportedAsSummaryFailure() {
        when(model.summarize(any())).thenReturn(result(validSummary(1, 1, 1, 10)));
        org.mockito.Mockito.doThrow(new IllegalStateException("ownership lost"))
                .doNothing()
                .when(executions)
                .assertOwnedAndWithinBudget(any());

        assertThatThrownBy(() -> recovery.resume(planned()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ownership lost");
        assertThat(job.getStatus()).isEqualTo(JobStatus.PLANNED.name());
        verify(executions, never()).fail(any(), anyString(), anyString());
    }

    private PlannedCompaction planned() {
        ContextExecutionRef execution =
                new ContextExecutionRef(TENANT_ID, SESSION_ID, EXECUTION_ID, OWNER_ID, 1L, 20L);
        ContextPolicySnapshot policy = new ContextPolicySnapshot(1, 3, 2, 10, 75, 65, 100, 109, 10);
        CompactionRequest request = mock(CompactionRequest.class);
        when(request.execution()).thenReturn(execution);
        when(request.policySnapshot()).thenReturn(policy);
        when(request.candidateInputs()).thenReturn(List.of(inputOne(), inputTwo()));
        when(request.planningInput()).thenReturn(mock(LsmPlanningInput.class));
        return new PlannedCompaction(JOB_ID, request, plan(), 7L, 3L, JobStatus.PLANNED, false);
    }

    private static LsmCompactionPlan plan() {
        var one = operation(TARGET_ONE, 1, 1);
        var two = operation(TARGET_TWO, 2, 2);
        var first = segment(TARGET_ONE, 1, 1);
        var second = segment(TARGET_TWO, 2, 2);
        return new LsmCompactionPlan(
                new LsmCompactionPlan.HotBoundary(null, null, 0, null),
                List.of(one, two),
                new LsmContextManifest(
                        null, List.of(TARGET_ONE, TARGET_TWO), null, null, null, null, 8, 3, 2),
                List.of(first, second));
    }

    private static LsmCompactionPlan.PlannedOperation operation(
            String target, long start, long end) {
        return new LsmCompactionPlan.PlannedOperation(
                LsmCompactionPlan.OperationType.SEAL_L0,
                target,
                List.of(),
                LsmSummarySegment.SegmentKind.STANDARD,
                0,
                new LsmSummarySegment.Coverage(start, end),
                false,
                10);
    }

    private static LsmSummarySegment segment(String id, long start, long end) {
        return new LsmSummarySegment(
                id,
                LsmSummarySegment.SegmentKind.STANDARD,
                0,
                new LsmSummarySegment.Coverage(start, end),
                10,
                List.of("raw-" + start),
                false);
    }

    private static CandidateInput inputOne() {
        return new CandidateInput(
                TARGET_ONE,
                new GenerationRequest(
                        LsmSummarySegment.SegmentKind.STANDARD,
                        0,
                        new LsmSummarySegment.Coverage(1, 1),
                        new MessageCoverage(1, 10),
                        List.of("raw-1"),
                        SOURCE_DOCUMENT));
    }

    private static CandidateInput inputTwo() {
        return new CandidateInput(
                TARGET_TWO,
                new GenerationRequest(
                        LsmSummarySegment.SegmentKind.STANDARD,
                        0,
                        new LsmSummarySegment.Coverage(2, 2),
                        new MessageCoverage(11, 20),
                        List.of("raw-2"),
                        SOURCE_DOCUMENT));
    }

    private String keyOne() {
        return generator.segmentKey(inputOne().generationRequest());
    }

    private String keyTwo() {
        return generator.segmentKey(inputTwo().generationRequest());
    }

    private static LsmCompactionJobEntity job(JobStatus status) {
        LsmCompactionJobEntity entity = new LsmCompactionJobEntity();
        entity.setCompactionJobId(JOB_ID);
        entity.setExecutionId(EXECUTION_ID);
        entity.setExecutionAttempt(1L);
        entity.setInputUpToMessageSequence(20L);
        entity.setOwnerInstanceId(OWNER_ID);
        entity.setStatus(status.name());
        entity.setVersion(0L);
        return entity;
    }

    private static LsmSummarySegmentEntity entity(
            String id, String key, long startTurn, long endTurn) {
        LsmSummarySegmentEntity entity = new LsmSummarySegmentEntity();
        entity.setSummarySegmentId(id);
        entity.setSegmentKey(key);
        entity.setKind(LsmSummarySegment.SegmentKind.STANDARD.name());
        entity.setLevel(0);
        entity.setStartTurnSequence(startTurn);
        entity.setEndTurnSequence(endTurn);
        entity.setEstimatedTokenCount(10);
        entity.setPartialSegment(false);
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    private static ContextSummaryResult result(String json) {
        return new ContextSummaryResult(json, 100, PROVIDER, MODEL);
    }

    private static String validSummary(
            long startTurn, long endTurn, long startMessage, long endMessage) {
        return """
                {"schemaVersion":1,"coverage":{"startTurnSequence":%d,"endTurnSequence":%d,"startMessageSequence":%d,"endMessageSequence":%d},"confirmedFacts":[],"userPreferences":[],"workingHypotheses":[],"decisions":[],"rejectedApproaches":[],"toolFindings":[],"openQuestions":[],"nextSteps":[]}
                """
                .formatted(startTurn, endTurn, startMessage, endMessage);
    }
}
