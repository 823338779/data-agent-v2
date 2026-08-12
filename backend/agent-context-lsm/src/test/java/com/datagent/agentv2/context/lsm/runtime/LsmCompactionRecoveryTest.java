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
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.SummarySource;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryDocumentValidator;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator.GenerationRequest;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator.MessageCoverage;
import com.datagent.agentv2.context.lsm.summary.LsmSegmentKeyFactory;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextPolicySnapshot;
import com.datagent.agentv2.conversation.context.ContextSummaryModelIdentity;
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
import org.mockito.ArgumentCaptor;

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
    private static final String NEXT_PROVIDER = "next-provider";
    private static final String NEXT_MODEL = "next-model";
    private static final ContextPolicySnapshot POLICY =
            new ContextPolicySnapshot(1, 3, 2, 10, 75, 65, 100, 109, 10);

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
        when(model.identity()).thenReturn(new ContextSummaryModelIdentity(PROVIDER, MODEL));
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
        when(segments.findBySegmentKey(org.mockito.ArgumentMatchers.eq(SESSION_ID), anyString()))
                .thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(1))));
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
    void differentModelIdentityDoesNotReuseExistingCandidate() {
        String oldKey = keyOne();
        stored.put(oldKey, entity(TARGET_ONE, oldKey, 1, 1));
        when(model.identity())
                .thenReturn(new ContextSummaryModelIdentity(NEXT_PROVIDER, NEXT_MODEL));
        when(model.summarize(any()))
                .thenReturn(
                        new ContextSummaryResult(
                                validSummary(1, 1, 1, 10), 100, NEXT_PROVIDER, NEXT_MODEL),
                        new ContextSummaryResult(
                                validSummary(2, 2, 11, 20), 100, NEXT_PROVIDER, NEXT_MODEL));

        ReadyCompaction ready = recovery.resume(planned());

        assertThat(ready.candidates()).hasSize(2);
        assertThat(stored).hasSize(3);
        verify(model, times(2)).summarize(any());
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

    @Test
    void cascadedParentUsesGeneratedChildSummaryInsteadOfChildRawSource() {
        String childSummary = validSummary(1, 1, 1, 10);
        String parentSummary = validSummary(1, 1, 1, 10);
        when(model.summarize(any())).thenReturn(result(childSummary), result(parentSummary));
        ArgumentCaptor<com.datagent.agentv2.conversation.context.ContextSummaryRequest> requests =
                ArgumentCaptor.forClass(
                        com.datagent.agentv2.conversation.context.ContextSummaryRequest.class);

        recovery.resume(cascadedPlanned());

        verify(model, times(2)).summarize(requests.capture());
        assertThat(requests.getAllValues().get(1).sourceDocument())
                .contains(childSummary.strip())
                .doesNotContain(SOURCE_DOCUMENT);
    }

    private PlannedCompaction planned() {
        ContextExecutionRef execution =
                new ContextExecutionRef(TENANT_ID, SESSION_ID, EXECUTION_ID, OWNER_ID, 1L, 20L);
        CompactionRequest request = mock(CompactionRequest.class);
        when(request.execution()).thenReturn(execution);
        when(request.policySnapshot()).thenReturn(POLICY);
        when(request.candidateInputs()).thenReturn(List.of(inputOne(), inputTwo()));
        when(request.planningInput()).thenReturn(mock(LsmPlanningInput.class));
        return new PlannedCompaction(JOB_ID, request, plan(), 7L, 3L, JobStatus.PLANNED, false);
    }

    private PlannedCompaction cascadedPlanned() {
        ContextExecutionRef execution =
                new ContextExecutionRef(TENANT_ID, SESSION_ID, EXECUTION_ID, OWNER_ID, 1L, 20L);
        CompactionRequest request = mock(CompactionRequest.class);
        when(request.execution()).thenReturn(execution);
        when(request.policySnapshot()).thenReturn(POLICY);
        when(request.candidateInputs()).thenReturn(List.of(inputOne(), parentInput()));
        when(request.planningInput()).thenReturn(mock(LsmPlanningInput.class));
        return new PlannedCompaction(
                JOB_ID, request, cascadedPlan(), 7L, 3L, JobStatus.PLANNED, false);
    }

    private static LsmCompactionPlan cascadedPlan() {
        var child = operation(TARGET_ONE, 1, 1);
        var parent =
                new LsmCompactionPlan.PlannedOperation(
                        LsmCompactionPlan.OperationType.PROMOTE,
                        TARGET_TWO,
                        List.of(TARGET_ONE),
                        LsmSummarySegment.SegmentKind.STANDARD,
                        1,
                        new LsmSummarySegment.Coverage(1, 1),
                        false,
                        10);
        var projected =
                new LsmSummarySegment(
                        TARGET_TWO,
                        LsmSummarySegment.SegmentKind.STANDARD,
                        1,
                        new LsmSummarySegment.Coverage(1, 1),
                        10,
                        List.of(TARGET_ONE),
                        false);
        return new LsmCompactionPlan(
                new LsmCompactionPlan.HotBoundary(null, null, 0, null),
                List.of(child, parent),
                new LsmContextManifest(null, List.of(TARGET_TWO), null, null, null, null, 8, 3, 2),
                List.of(projected));
    }

    private static CandidateInput parentInput() {
        return new CandidateInput(
                TARGET_TWO,
                new GenerationRequest(
                        LsmSummarySegment.SegmentKind.STANDARD,
                        1,
                        new LsmSummarySegment.Coverage(1, 1),
                        new MessageCoverage(1, 10),
                        List.of(TARGET_ONE),
                        "[{\"plannedSummarySegmentId\":\"target-one\"}]"),
                List.of(new SummarySource(TARGET_ONE, null)));
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
        return generator.segmentKey(inputOne().generationRequest(), POLICY);
    }

    private String keyTwo() {
        return generator.segmentKey(inputTwo().generationRequest(), POLICY);
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
        entity.setStartMessageSequence(startTurn == 1L ? 1L : 11L);
        entity.setEndMessageSequence(endTurn == 1L ? 10L : 20L);
        entity.setEstimatedTokenCount(10);
        entity.setPartialSegment(false);
        entity.setSummaryDocument(
                validSummary(
                        startTurn,
                        endTurn,
                        entity.getStartMessageSequence(),
                        entity.getEndMessageSequence()));
        entity.setSourceContentSha256(sha256(SOURCE_DOCUMENT));
        entity.setSummarySchemaVersion(ContextSummaryDocumentValidator.SCHEMA_VERSION);
        entity.setPromptVersion(
                com.datagent.agentv2.context.lsm.summary.ContextSummaryPrompt.PROMPT_VERSION);
        entity.setModelProvider(PROVIDER);
        entity.setModelName(MODEL);
        entity.setCreationPolicy(new LsmDocumentCodec().writePolicy(POLICY));
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            value.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
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
