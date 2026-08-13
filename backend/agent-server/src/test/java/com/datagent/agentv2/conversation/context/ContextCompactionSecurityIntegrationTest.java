package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan;
import com.datagent.agentv2.context.lsm.domain.LsmContextManifest;
import com.datagent.agentv2.context.lsm.domain.LsmContextPolicySnapshot;
import com.datagent.agentv2.context.lsm.domain.LsmPlanningInput;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment;
import com.datagent.agentv2.context.lsm.observability.LsmContextMetrics;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmCompactionJobEntity;
import com.datagent.agentv2.context.lsm.persistence.json.LsmDocumentCodec;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmCompactionJobRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmSummarySegmentRepository;
import com.datagent.agentv2.context.lsm.planning.ConversationTokenEstimator;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.CandidateInput;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.CompactionRequest;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.JobStatus;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.PlannedCompaction;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionException;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionPlanningTransaction;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionRecoveryService;
import com.datagent.agentv2.context.lsm.runtime.LsmManifestPublicationTransaction;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryDocumentValidator;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator.GenerationRequest;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator.MessageCoverage;
import com.datagent.agentv2.context.lsm.summary.LsmSegmentKeyFactory;
import com.datagent.agentv2.platform.id.IdGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ContextCompactionSecurityIntegrationTest {

    private static final String MESSAGE_MARKER = "message-marker-7f3a";
    private static final String THINKING_MARKER = "thinking-marker-8b4c";
    private static final String TOOL_MARKER = "tool-marker-9d5e";
    private static final String JOB_ID = "job-safe";
    private static final String TARGET_ID = "target-safe";
    private static final String PROVIDER_ERROR = "provider failed: ";
    private static final String NESTED_CAUSE_CAPTURE_SENTINEL = "nested-cause-capture-sentinel";
    private static final List<String> MARKERS =
            List.of(MESSAGE_MARKER, THINKING_MARKER, TOOL_MARKER);

    @Test
    void failedCompactionDoesNotExposeConversationBodiesInLogsEventsOrMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LsmContextMetrics metrics = new LsmContextMetrics(registry);
        RecordingExecutionControl executions = new RecordingExecutionControl();
        CompactionRequest request = request();
        PlannedCompaction planned =
                new PlannedCompaction(JOB_ID, request, plan(), 1L, 1L, JobStatus.PLANNED, false);
        LsmCompactionPlanningTransaction planning = mock(LsmCompactionPlanningTransaction.class);
        when(planning.begin(request)).thenReturn(planned);
        LsmManifestPublicationTransaction publication =
                mock(LsmManifestPublicationTransaction.class);
        LsmCompactionRecoveryService recovery = recovery(executions);
        LsmCompactionCoordinator coordinator =
                new LsmCompactionCoordinator(planning, recovery, publication, metrics);
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);

        try {
            assertThatThrownBy(() -> coordinator.compactIfRequired(request))
                    .isInstanceOf(LsmCompactionException.class)
                    .hasMessage("Context compaction failed")
                    .hasNoCause();
            root.warn(
                    "Throwable rendering probe",
                    new IllegalStateException(
                            "outer-safe",
                            new IllegalArgumentException(NESTED_CAUSE_CAPTURE_SENTINEL)));
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        String logs =
                appender.list.stream()
                        .map(
                                event ->
                                        event.getFormattedMessage()
                                                + (event.getThrowableProxy() == null
                                                        ? ""
                                                        : ThrowableProxyUtil.asString(
                                                                event.getThrowableProxy())))
                        .reduce("", String::concat);
        String events = String.join("|", executions.safeEvents);
        String meters =
                registry.getMeters().stream()
                        .map(meter -> meter.getId().toString())
                        .reduce("", String::concat);
        for (String marker : MARKERS) {
            assertThat(logs).doesNotContain(marker);
            assertThat(events).doesNotContain(marker);
            assertThat(meters).doesNotContain(marker);
        }
        assertThat(logs).contains(NESTED_CAUSE_CAPTURE_SENTINEL);
        assertThat(events)
                .contains(LsmCompactionException.FAILURE_CODE)
                .contains("Context compaction failed");
        assertThat(
                        registry.get("agent_context_compaction_total")
                                .tag("result", "failed")
                                .tag("type", "seal_l0")
                                .counter()
                                .count())
                .isEqualTo(1d);
    }

    private static LsmCompactionRecoveryService recovery(RecordingExecutionControl executions) {
        LsmSummarySegmentRepository segments = mock(LsmSummarySegmentRepository.class);
        LsmCompactionJobRepository jobs = mock(LsmCompactionJobRepository.class);
        LsmCompactionJobEntity job = new LsmCompactionJobEntity();
        job.setCompactionJobId(JOB_ID);
        job.setExecutionId("execution-safe");
        job.setExecutionAttempt(1L);
        job.setInputUpToMessageSequence(2L);
        job.setOwnerInstanceId("owner-safe");
        job.setStatus(JobStatus.PLANNED.name());
        job.setVersion(0L);
        when(jobs.findBoundaryJob("execution-safe", 1L, 2L)).thenReturn(Optional.of(job));
        when(jobs.transition(any(), anyString(), anyString(), anyString(), any(), anyBoolean()))
                .thenAnswer(
                        invocation -> {
                            job.setStatus(invocation.getArgument(3));
                            return true;
                        });
        when(segments.findBySegmentKey(anyString(), anyString())).thenReturn(Optional.empty());
        ContextSummaryModelPort model =
                new ContextSummaryModelPort() {
                    @Override
                    public ContextSummaryModelIdentity identity() {
                        return new ContextSummaryModelIdentity("security-test", "failure-model");
                    }

                    @Override
                    public ContextSummaryResult summarize(ContextSummaryRequest request) {
                        throw new IllegalStateException(PROVIDER_ERROR + request.sourceDocument());
                    }
                };
        ContextSummaryGenerator generator =
                new ContextSummaryGenerator(
                        model,
                        new ContextSummaryDocumentValidator(),
                        new ConversationTokenEstimator(),
                        new LsmSegmentKeyFactory());
        IdGenerator ids = prefix -> prefix + "-safe";
        return new LsmCompactionRecoveryService(
                generator, segments, jobs, executions, new LsmDocumentCodec(), ids);
    }

    private static CompactionRequest request() {
        ContextExecutionRef execution =
                new ContextExecutionRef(
                        "tenant-safe", "session-safe", "execution-safe", "owner-safe", 1L, 2L);
        LsmContextPolicySnapshot policy =
                new LsmContextPolicySnapshot(1, 5, 4, 10, 75, 65, 100, 109, 10);
        LsmPlanningInput planningInput = mock(LsmPlanningInput.class);
        when(planningInput.manifest()).thenReturn(LsmContextManifest.empty());
        CandidateInput input =
                new CandidateInput(
                        TARGET_ID,
                        new GenerationRequest(
                                LsmSummarySegment.SegmentKind.STANDARD,
                                0,
                                new LsmSummarySegment.Coverage(1, 1),
                                new MessageCoverage(1, 2),
                                List.of("raw-1"),
                                sourceDocument()));
        CompactionRequest request = mock(CompactionRequest.class);
        when(request.execution()).thenReturn(execution);
        when(request.policySnapshot()).thenReturn(policy);
        when(request.planningInput()).thenReturn(planningInput);
        when(request.candidateInputs()).thenReturn(List.of(input));
        return request;
    }

    private static LsmCompactionPlan plan() {
        LsmCompactionPlan.PlannedOperation operation =
                new LsmCompactionPlan.PlannedOperation(
                        LsmCompactionPlan.OperationType.SEAL_L0,
                        TARGET_ID,
                        List.of(),
                        LsmSummarySegment.SegmentKind.STANDARD,
                        0,
                        new LsmSummarySegment.Coverage(1, 1),
                        false,
                        10);
        LsmSummarySegment segment =
                new LsmSummarySegment(
                        TARGET_ID,
                        LsmSummarySegment.SegmentKind.STANDARD,
                        0,
                        new LsmSummarySegment.Coverage(1, 1),
                        10,
                        List.of("raw-1"),
                        false);
        return new LsmCompactionPlan(
                new LsmCompactionPlan.HotBoundary(null, null, 0, null),
                List.of(operation),
                new LsmContextManifest(null, List.of(TARGET_ID), null, null, null, null, 1, 5, 4),
                List.of(segment));
    }

    private static String sourceDocument() {
        return """
                {"messages":[{"content":"%s","thinking":"%s","toolCalls":[{"arguments":{"secret":"%s"}}]}]}
                """
                .formatted(MESSAGE_MARKER, THINKING_MARKER, TOOL_MARKER);
    }

    private static final class RecordingExecutionControl implements ContextExecutionControlPort {

        private final List<String> safeEvents = new ArrayList<>();

        @Override
        public void assertOwnedAndWithinBudget(ContextExecutionRef execution) {}

        @Override
        public boolean cancelIfRequested(ContextExecutionRef execution) {
            return false;
        }

        @Override
        public void publish(ContextExecutionRef execution, ContextLifecycleEvent event) {
            safeEvents.add(event.type().name() + event.metadata());
        }

        @Override
        public void fail(ContextExecutionRef execution, String failureCode, String safeMessage) {
            safeEvents.add(failureCode + ':' + safeMessage);
        }
    }
}
