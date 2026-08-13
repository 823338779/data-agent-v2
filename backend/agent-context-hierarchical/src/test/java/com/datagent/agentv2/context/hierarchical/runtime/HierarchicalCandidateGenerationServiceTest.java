package com.datagent.agentv2.context.hierarchical.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.context.hierarchical.domain.FrontierSelection;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalColdCandidate;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionJob;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionPlan;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionPlan.TurnRange;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalSummaryNode;
import com.datagent.agentv2.context.hierarchical.domain.SummaryRange;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCompactionJobRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalSummaryNodeRepository;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction.GenerationSettings;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction.PlannedCandidate;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction.PlannedCompaction;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GeneratedSummary;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GenerationRange;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GenerationRequest;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.ParentSource;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.SourceKind;
import com.datagent.agentv2.context.hierarchical.summary.SummaryGenerationIdentity;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextSummaryModelIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class HierarchicalCandidateGenerationServiceTest {

    private static final String SESSION_ID = "session-1";
    private static final String GENERATOR_FAILURE_CODE = "FAILURE_CONTEXT_COMPACTION";
    private static final String FAILURE_CODE = HierarchicalCompactionException.FAILURE_CODE;
    private static final HierarchicalContextPolicySnapshot POLICY =
            new HierarchicalContextPolicySnapshot(1, 0.75d, 0.70d, 10, 100, 1, 1);
    private static final ContextExecutionRef EXECUTION =
            new ContextExecutionRef("tenant-1", SESSION_ID, "execution-1", "owner-1", 1L, 40L);
    private static final SummaryRange LEFT = new SummaryRange(1, 1, 1, 10, 0);
    private static final SummaryRange RIGHT = new SummaryRange(2, 2, 11, 20, 0);
    private static final SummaryRange PARENT = new SummaryRange(1, 2, 1, 20, 1);

    private HierarchicalSummaryGenerator generator;
    private HierarchicalSummaryNodeRepository nodes;
    private HierarchicalCompactionJobRepository jobs;
    private ContextExecutionControlPort executions;
    private HierarchicalGenerationSourceProvider sources;
    private HierarchicalCandidateGenerationService service;
    private AtomicReference<HierarchicalColdCandidate> coldCandidate;

    @BeforeEach
    void setUp() {
        generator = mock(HierarchicalSummaryGenerator.class);
        nodes = mock(HierarchicalSummaryNodeRepository.class);
        jobs = mock(HierarchicalCompactionJobRepository.class);
        executions = mock(ContextExecutionControlPort.class);
        sources = mock(HierarchicalGenerationSourceProvider.class);
        coldCandidate = new AtomicReference<>();
        service =
                new HierarchicalCandidateGenerationService(
                        generator, nodes, jobs, executions, sources);
        when(sources.load(any()))
                .thenReturn(
                        new HierarchicalGenerationSourceProvider.GenerationSources(
                                List.of(turn(1, 1, 10), turn(2, 11, 20)), checkpoint()));
        when(nodes.findByIds(eq(SESSION_ID), any())).thenReturn(List.of());
        when(nodes.insertOrSelect(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobs.findJob("job-1")).thenAnswer(invocation -> planned().job());
        when(jobs.transitionJob(any(), any(), any(), eq(EXECUTION), any())).thenReturn(true);
        when(jobs.storeColdCandidate(eq("job-1"), eq(EXECUTION), any()))
                .thenAnswer(
                        invocation -> {
                            coldCandidate.set(invocation.getArgument(2));
                            return true;
                        });
        when(jobs.findColdCandidate("job-1")).thenAnswer(invocation -> coldCandidate.get());
    }

    @Test
    void generatesMissingLeavesThenOrderedParentAndColdOutsideDatabaseTransaction() {
        List<GenerationRequest> requests = new ArrayList<>();
        when(generator.generate(any(), any()))
                .thenAnswer(
                        invocation -> {
                            GenerationRequest request = invocation.getArgument(0);
                            Runnable beforeModelCall = invocation.getArgument(1);
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            beforeModelCall.run();
                            requests.add(request);
                            return generated(request);
                        });

        HierarchicalCandidateGenerationService.ReadyCompaction ready = service.generate(planned());

        assertThat(ready.cancelled()).isFalse();
        assertThat(ready.nodeIds()).containsExactly("left", "right", "parent");
        assertThat(requests)
                .extracting(request -> request.source().kind())
                .containsExactly(
                        SourceKind.LEAF, SourceKind.LEAF, SourceKind.PARENT, SourceKind.COLD);
        ParentSource parent = (ParentSource) requests.get(2).source();
        assertThat(parent.left().nodeId()).isEqualTo("left");
        assertThat(parent.right().nodeId()).isEqualTo("right");
        verify(executions, times(6)).assertOwnedAndWithinBudget(EXECUTION);
        verify(executions, times(6)).cancelIfRequested(EXECUTION);
        InOrder transitions = inOrder(jobs);
        transitions
                .verify(jobs)
                .transitionJob(
                        "job-1",
                        HierarchicalCompactionJob.Status.PLANNED,
                        HierarchicalCompactionJob.Status.GENERATING,
                        EXECUTION,
                        null);
        transitions
                .verify(jobs)
                .transitionJob(
                        "job-1",
                        HierarchicalCompactionJob.Status.GENERATING,
                        HierarchicalCompactionJob.Status.READY_TO_PUBLISH,
                        EXECUTION,
                        null);
    }

    @Test
    void reusesAnExistingLeafWithoutCallingTheModelForIt() {
        when(nodes.findByIds(eq(SESSION_ID), any()))
                .thenReturn(List.of(node("left", LEFT, null, null)));
        when(generator.generate(any(), any()))
                .thenAnswer(
                        invocation -> {
                            GenerationRequest request = invocation.getArgument(0);
                            invocation.<Runnable>getArgument(1).run();
                            return generated(request);
                        });

        service.generate(planned());

        verify(generator, times(3)).generate(any(), any());
    }

    @Test
    void cancellationMarksTheGeneratingJobCancelledBeforeAnyModelCall() {
        when(executions.cancelIfRequested(EXECUTION)).thenReturn(true);
        when(generator.generate(any(), any()))
                .thenAnswer(
                        invocation -> {
                            invocation.<Runnable>getArgument(1).run();
                            throw new AssertionError("cancelled generation must not continue");
                        });

        HierarchicalCandidateGenerationService.ReadyCompaction ready = service.generate(planned());

        assertThat(ready.cancelled()).isTrue();
        verify(generator, never()).generate(any(), any());
        verify(jobs)
                .transitionJob(
                        "job-1",
                        HierarchicalCompactionJob.Status.GENERATING,
                        HierarchicalCompactionJob.Status.CANCELLED,
                        EXECUTION,
                        null);
    }

    @Test
    void stableGeneratorFailureMarksTheJobFailedAndBecomesCompactionException() {
        when(generator.generate(any(), any()))
                .thenThrow(new IllegalStateException(GENERATOR_FAILURE_CODE));

        assertThatThrownBy(() -> service.generate(planned()))
                .isInstanceOf(HierarchicalCompactionException.class)
                .hasMessage(FAILURE_CODE);
        verify(jobs)
                .transitionJob(
                        "job-1",
                        HierarchicalCompactionJob.Status.GENERATING,
                        HierarchicalCompactionJob.Status.FAILED,
                        EXECUTION,
                        FAILURE_CODE);
        verify(executions).fail(EXECUTION, FAILURE_CODE, "Hierarchical context compaction failed");
    }

    @Test
    void allReusedCandidatesStillCheckCancellationAtEntryAndNeverBecomeReady() {
        when(nodes.findByIds(eq(SESSION_ID), any()))
                .thenReturn(
                        List.of(
                                node("left", LEFT, null, null),
                                node("right", RIGHT, null, null),
                                node("parent", PARENT, "left", "right")));
        when(executions.cancelIfRequested(EXECUTION)).thenReturn(true);

        HierarchicalCandidateGenerationService.ReadyCompaction ready = service.generate(planned());

        assertThat(ready.cancelled()).isTrue();
        verify(generator, never()).generate(any(), any());
        verify(jobs, never())
                .transitionJob(
                        "job-1",
                        HierarchicalCompactionJob.Status.GENERATING,
                        HierarchicalCompactionJob.Status.READY_TO_PUBLISH,
                        EXECUTION,
                        null);
    }

    @Test
    void fullyDurableRecoveryValidatesAndBecomesReadyWithoutEphemeralSources() {
        when(nodes.findByIds(eq(SESSION_ID), any()))
                .thenReturn(
                        List.of(
                                node("left", LEFT, null, null),
                                node("right", RIGHT, null, null),
                                node("parent", PARENT, "left", "right")));
        coldCandidate.set(
                new HierarchicalColdCandidate(
                        "cold",
                        1,
                        2,
                        1,
                        20,
                        "{\"schemaVersion\":1}",
                        20,
                        1,
                        1,
                        "provider",
                        "model"));
        when(sources.load(any()))
                .thenThrow(new AssertionError("fully durable recovery must not load sources"));

        HierarchicalCandidateGenerationService.ReadyCompaction ready = service.generate(planned());

        assertThat(ready.cancelled()).isFalse();
        verify(sources, never()).load(any());
        verify(generator, never()).generate(any(), any());
        verify(executions, times(2)).assertOwnedAndWithinBudget(EXECUTION);
        verify(executions, times(2)).cancelIfRequested(EXECUTION);
    }

    @Test
    void rejectsMalformedExistingFrontierWhenItsDescriptorIsMissingFromDurablePlan() {
        HierarchicalSummaryNode malformed =
                new HierarchicalSummaryNode(
                        "frontier-parent",
                        "other-session",
                        1,
                        2,
                        1,
                        20,
                        "right",
                        "left",
                        "{\"schemaVersion\":1}",
                        20,
                        1,
                        1,
                        "provider",
                        "model");
        when(nodes.findByIds(eq(SESSION_ID), any())).thenReturn(List.of(malformed));

        assertThatThrownBy(() -> service.generate(frontierWithoutDescriptor()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Hierarchical candidates are incomplete");
        verify(jobs, never())
                .transitionJob(
                        "job-1",
                        HierarchicalCompactionJob.Status.GENERATING,
                        HierarchicalCompactionJob.Status.READY_TO_PUBLISH,
                        EXECUTION,
                        null);
        verify(sources, never()).load(any());
    }

    @Test
    void rejectsMalformedSameIdExistingFrontierAgainstItsDurableDescriptor() {
        HierarchicalSummaryNode malformed =
                new HierarchicalSummaryNode(
                        "frontier-parent",
                        SESSION_ID,
                        1,
                        2,
                        1,
                        20,
                        "right",
                        "left",
                        "{\"schemaVersion\":1}",
                        20,
                        1,
                        1,
                        "provider",
                        "model");
        when(nodes.findByIds(eq(SESSION_ID), any())).thenReturn(List.of(malformed));

        assertThatThrownBy(() -> service.generate(frontierWithDescriptor()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Hierarchical candidates are incomplete");
        verify(sources, never()).load(any());
    }

    @Test
    void rejectsWarmTargetThatDoesNotMatchOtherwiseValidSelectedFrontier() {
        PlannedCompaction baseline = frontierWithDescriptor();
        HierarchicalCompactionPlan tampered =
                new HierarchicalCompactionPlan(
                        1,
                        Optional.empty(),
                        TurnRange.EMPTY,
                        new TurnRange(1, 1),
                        TurnRange.EMPTY,
                        List.of(),
                        baseline.plan().warmTargetRanges(),
                        baseline.plan().frontier(),
                        List.of());
        when(nodes.findByIds(eq(SESSION_ID), any()))
                .thenReturn(List.of(node("frontier-parent", PARENT, "left", "right")));

        assertThatThrownBy(() -> service.generate(withPlan(baseline, tampered)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Hierarchical candidates are incomplete");
        verify(sources, never()).load(any());
    }

    @Test
    void rejectsColdTargetThatDoesNotMatchCandidateAndPromotions() {
        PlannedCompaction baseline = planned();
        HierarchicalCompactionPlan tampered =
                new HierarchicalCompactionPlan(
                        1,
                        Optional.empty(),
                        new TurnRange(1, 1),
                        TurnRange.EMPTY,
                        TurnRange.EMPTY,
                        baseline.plan().coldPromotionRanges(),
                        List.of(),
                        FrontierSelection.empty(),
                        List.of());
        when(nodes.findByIds(eq(SESSION_ID), any()))
                .thenReturn(
                        List.of(
                                node("left", LEFT, null, null),
                                node("right", RIGHT, null, null),
                                node("parent", PARENT, "left", "right")));
        coldCandidate.set(validColdCandidate());

        assertThatThrownBy(() -> service.generate(withPlan(baseline, tampered)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Hierarchical candidates are incomplete");
        verify(sources, never()).load(any());
    }

    @Test
    void rejectsHotEmergencyDescriptorOutsideDeclaredHotTarget() {
        PlannedCompaction baseline = leafOnlyPlanned();
        HierarchicalCompactionPlan tampered =
                new HierarchicalCompactionPlan(
                        1,
                        Optional.empty(),
                        TurnRange.EMPTY,
                        TurnRange.EMPTY,
                        new TurnRange(1, 1),
                        List.of(),
                        List.of(),
                        FrontierSelection.empty(),
                        List.of(RIGHT));
        PlannedCompaction tamperedCompaction =
                new PlannedCompaction(
                        baseline.job(),
                        baseline.execution(),
                        baseline.generationSettings(),
                        baseline.warmTokenBudget(),
                        tampered,
                        List.of(plannedCandidate("right", RIGHT, SourceKind.LEAF, null, null)),
                        null);
        when(nodes.findByIds(eq(SESSION_ID), any()))
                .thenReturn(List.of(node("right", RIGHT, null, null)));

        assertThatThrownBy(() -> service.generate(tamperedCompaction))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Hierarchical candidates are incomplete");
        verify(sources, never()).load(any());
    }

    @Test
    void rejectsSameIdDurableNodeWithMalformedRangeIdentityOrChildren() {
        HierarchicalSummaryNode malformed =
                new HierarchicalSummaryNode(
                        "parent",
                        "other-session",
                        1,
                        2,
                        1,
                        20,
                        "right",
                        "left",
                        "{\"schemaVersion\":1}",
                        20,
                        1,
                        1,
                        "provider",
                        "model");
        when(nodes.findByIds(eq(SESSION_ID), any()))
                .thenReturn(
                        List.of(
                                node("left", LEFT, null, null),
                                node("right", RIGHT, null, null),
                                malformed));

        assertThatThrownBy(() -> service.generate(planned()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Hierarchical candidates are incomplete");
        verify(jobs, never())
                .transitionJob(
                        "job-1",
                        HierarchicalCompactionJob.Status.GENERATING,
                        HierarchicalCompactionJob.Status.READY_TO_PUBLISH,
                        EXECUTION,
                        null);
    }

    @Test
    void rejectsSameIdColdCandidateWithMalformedEnvelopeBeforeReady() {
        coldCandidate.set(
                new HierarchicalColdCandidate(
                        "cold",
                        1,
                        1,
                        1,
                        10,
                        "{\"schemaVersion\":1}",
                        20,
                        1,
                        1,
                        "provider",
                        "model"));
        when(nodes.findByIds(eq(SESSION_ID), any()))
                .thenReturn(
                        List.of(
                                node("left", LEFT, null, null),
                                node("right", RIGHT, null, null),
                                node("parent", PARENT, "left", "right")));

        assertThatThrownBy(() -> service.generate(planned()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Hierarchical candidates are incomplete");
        verify(jobs, never())
                .transitionJob(
                        "job-1",
                        HierarchicalCompactionJob.Status.GENERATING,
                        HierarchicalCompactionJob.Status.READY_TO_PUBLISH,
                        EXECUTION,
                        null);
    }

    @Test
    void springProxySuspendsAmbientTransactionForGenerationAndModelCallback() {
        HierarchicalCompactionPlanningTransaction.PlannedCompaction noCold = leafOnlyPlanned();
        when(generator.generate(any(), any()))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            assertThat(
                                            HierarchicalCompactionPlanningTransactionTest
                                                    .RecordingTransactionManager.currentId())
                                    .isNull();
                            invocation.<Runnable>getArgument(1).run();
                            return generated(invocation.getArgument(0));
                        });

        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.register(GenerationTransactionTestConfiguration.class);
            context.registerBean(HierarchicalSummaryGenerator.class, () -> generator);
            context.registerBean(HierarchicalSummaryNodeRepository.class, () -> nodes);
            context.registerBean(HierarchicalCompactionJobRepository.class, () -> jobs);
            context.registerBean(ContextExecutionControlPort.class, () -> executions);
            context.registerBean(HierarchicalGenerationSourceProvider.class, () -> sources);
            context.registerBean(
                    HierarchicalCandidateGenerationService.class,
                    () ->
                            new HierarchicalCandidateGenerationService(
                                    generator, nodes, jobs, executions, sources));
            context.refresh();
            HierarchicalCompactionPlanningTransactionTest.RecordingTransactionManager transactions =
                    context.getBean(
                            HierarchicalCompactionPlanningTransactionTest
                                    .RecordingTransactionManager.class);
            HierarchicalCandidateGenerationService proxy =
                    context.getBean(HierarchicalCandidateGenerationService.class);
            AtomicReference<String> outer = new AtomicReference<>();

            new TransactionTemplate(transactions)
                    .executeWithoutResult(
                            ignored -> {
                                outer.set(
                                        HierarchicalCompactionPlanningTransactionTest
                                                .RecordingTransactionManager.currentId());
                                proxy.generate(noCold);
                                assertThat(
                                                HierarchicalCompactionPlanningTransactionTest
                                                        .RecordingTransactionManager.currentId())
                                        .isEqualTo(outer.get());
                            });
        }
    }

    private static PlannedCompaction planned() {
        HierarchicalCompactionPlan plan =
                new HierarchicalCompactionPlan(
                        2,
                        Optional.empty(),
                        new TurnRange(1, 2),
                        TurnRange.EMPTY,
                        TurnRange.EMPTY,
                        List.of(PARENT),
                        List.of(),
                        FrontierSelection.empty(),
                        List.of());
        HierarchicalCompactionJob job =
                new HierarchicalCompactionJob(
                        "job-1",
                        SESSION_ID,
                        EXECUTION.executionId(),
                        EXECUTION.executionAttempt(),
                        EXECUTION.ownerInstanceId(),
                        EXECUTION.inputUpToMessageSequence(),
                        checkpoint().version(),
                        checkpoint().strategyGeneration(),
                        "{\"schemaVersion\":1}",
                        HierarchicalCompactionJob.Status.PLANNED,
                        null,
                        0);
        return new PlannedCompaction(
                job,
                EXECUTION,
                generationSettings(),
                600,
                plan,
                List.of(
                        plannedCandidate("left", LEFT, SourceKind.LEAF, null, null),
                        plannedCandidate("right", RIGHT, SourceKind.LEAF, null, null),
                        plannedCandidate("parent", PARENT, SourceKind.PARENT, "left", "right")),
                plannedColdCandidate());
    }

    private static PlannedCompaction leafOnlyPlanned() {
        HierarchicalCompactionPlan plan =
                new HierarchicalCompactionPlan(
                        1,
                        Optional.empty(),
                        TurnRange.EMPTY,
                        new TurnRange(1, 1),
                        TurnRange.EMPTY,
                        List.of(),
                        List.of(LEFT),
                        new FrontierSelection(List.of(), List.of(), List.of(LEFT), 0, false, true),
                        List.of());
        HierarchicalCompactionJob job =
                new HierarchicalCompactionJob(
                        "job-1",
                        SESSION_ID,
                        EXECUTION.executionId(),
                        EXECUTION.executionAttempt(),
                        EXECUTION.ownerInstanceId(),
                        EXECUTION.inputUpToMessageSequence(),
                        checkpoint().version(),
                        checkpoint().strategyGeneration(),
                        "{\"schemaVersion\":1}",
                        HierarchicalCompactionJob.Status.PLANNED,
                        null,
                        0);
        return new PlannedCompaction(
                job,
                EXECUTION,
                generationSettings(),
                600,
                plan,
                List.of(plannedCandidate("left", LEFT, SourceKind.LEAF, null, null)),
                null);
    }

    private static PlannedCompaction frontierWithoutDescriptor() {
        return frontierPlanned(List.of());
    }

    private static PlannedCompaction frontierWithDescriptor() {
        return frontierPlanned(
                List.of(
                        plannedCandidate(
                                "frontier-parent", PARENT, SourceKind.PARENT, "left", "right")));
    }

    private static PlannedCompaction frontierPlanned(List<PlannedCandidate> candidates) {
        HierarchicalCompactionPlan plan =
                new HierarchicalCompactionPlan(
                        2,
                        Optional.empty(),
                        TurnRange.EMPTY,
                        new TurnRange(1, 2),
                        TurnRange.EMPTY,
                        List.of(),
                        List.of(PARENT),
                        new FrontierSelection(
                                List.of("frontier-parent"),
                                List.of(PARENT),
                                List.of(),
                                20,
                                true,
                                true),
                        List.of());
        HierarchicalCompactionJob job =
                new HierarchicalCompactionJob(
                        "job-1",
                        SESSION_ID,
                        EXECUTION.executionId(),
                        EXECUTION.executionAttempt(),
                        EXECUTION.ownerInstanceId(),
                        EXECUTION.inputUpToMessageSequence(),
                        checkpoint().version(),
                        checkpoint().strategyGeneration(),
                        "{\"schemaVersion\":1}",
                        HierarchicalCompactionJob.Status.PLANNED,
                        null,
                        0);
        return new PlannedCompaction(
                job, EXECUTION, generationSettings(), 600, plan, candidates, null);
    }

    private static PlannedCompaction withPlan(
            PlannedCompaction baseline, HierarchicalCompactionPlan plan) {
        return new PlannedCompaction(
                baseline.job(),
                baseline.execution(),
                baseline.generationSettings(),
                baseline.warmTokenBudget(),
                plan,
                baseline.candidates(),
                baseline.coldCandidate());
    }

    private static HierarchicalColdCandidate validColdCandidate() {
        return new HierarchicalColdCandidate(
                "cold", 1, 2, 1, 20, "{\"schemaVersion\":1}", 20, 1, 1, "provider", "model");
    }

    private static GenerationSettings generationSettings() {
        return new GenerationSettings(POLICY, 256, 1, 1, "provider", "model");
    }

    private static PlannedCandidate plannedCandidate(
            String nodeId,
            SummaryRange range,
            SourceKind sourceKind,
            String leftChildNodeId,
            String rightChildNodeId) {
        return new PlannedCandidate(
                nodeId,
                new GenerationRange(
                        range.startTurn(),
                        range.endTurn(),
                        range.startMessageSequence(),
                        range.endMessageSequence(),
                        range.level()),
                sourceKind,
                leftChildNodeId,
                rightChildNodeId,
                POLICY.summarySchemaVersion(),
                POLICY.summaryPromptVersion(),
                "provider",
                "model");
    }

    private static PlannedCandidate plannedColdCandidate() {
        return new PlannedCandidate(
                "cold",
                new GenerationRange(1, 2, 1, 20, 0),
                SourceKind.COLD,
                null,
                null,
                POLICY.summarySchemaVersion(),
                POLICY.summaryPromptVersion(),
                "provider",
                "model");
    }

    private static GeneratedSummary generated(GenerationRequest request) {
        return new GeneratedSummary(
                switch (request.source().kind()) {
                    case LEAF -> request.range().startTurn() == 1 ? "left" : "right";
                    case PARENT -> "parent";
                    case COLD -> "cold";
                },
                "{\"schemaVersion\":1}",
                20,
                new SummaryGenerationIdentity(
                        SESSION_ID, 1, 1, new ContextSummaryModelIdentity("provider", "model")),
                request.range());
    }

    private static HierarchicalSummaryNode node(
            String id, SummaryRange range, String leftId, String rightId) {
        return new HierarchicalSummaryNode(
                id,
                SESSION_ID,
                range.level(),
                range.coveredTurns(),
                range.startMessageSequence(),
                range.endMessageSequence(),
                leftId,
                rightId,
                "{\"schemaVersion\":1}",
                20,
                1,
                1,
                "provider",
                "model");
    }

    private static ConversationTurn turn(long ordinal, long start, long end) {
        return new ConversationTurn(
                ordinal, start, end, true, List.of(ContextMessage.user("turn-" + ordinal)));
    }

    private static HierarchicalCheckpoint checkpoint() {
        return new HierarchicalCheckpoint(
                "checkpoint-1",
                SESSION_ID,
                "{}",
                0,
                0,
                0,
                1,
                1,
                "provider",
                "model",
                "{\"schemaVersion\":1,\"nodeIds\":[]}",
                5,
                7);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class GenerationTransactionTestConfiguration {

        @org.springframework.context.annotation.Bean
        HierarchicalCompactionPlanningTransactionTest.RecordingTransactionManager
                transactionManager() {
            return new HierarchicalCompactionPlanningTransactionTest.RecordingTransactionManager();
        }
    }
}
