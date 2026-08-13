package com.datagent.agentv2.context.hierarchical.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
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
import com.datagent.agentv2.context.hierarchical.planning.BinarySummaryTreePlanner.PlanningInput;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction.CompactionRequest;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction.PlannedCandidate;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction.PlannedCompaction;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GeneratedSummary;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GenerationRequest;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.ParentSource;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.SourceKind;
import com.datagent.agentv2.context.hierarchical.summary.SummaryGenerationIdentity;
import com.datagent.agentv2.conversation.context.ContextBudget;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

class HierarchicalCandidateGenerationServiceTest {

    private static final String SESSION_ID = "session-1";
    private static final String FAILURE_CODE = "FAILURE_CONTEXT_COMPACTION";
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
    private HierarchicalCandidateGenerationService service;
    private AtomicReference<HierarchicalColdCandidate> coldCandidate;

    @BeforeEach
    void setUp() {
        generator = mock(HierarchicalSummaryGenerator.class);
        nodes = mock(HierarchicalSummaryNodeRepository.class);
        jobs = mock(HierarchicalCompactionJobRepository.class);
        executions = mock(ContextExecutionControlPort.class);
        coldCandidate = new AtomicReference<>();
        service = new HierarchicalCandidateGenerationService(generator, nodes, jobs, executions);
        when(nodes.findByIds(eq(SESSION_ID), any())).thenReturn(List.of());
        when(nodes.insertOrSelect(any())).thenAnswer(invocation -> invocation.getArgument(0));
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
        verify(executions, times(4)).assertOwnedAndWithinBudget(EXECUTION);
        verify(executions, times(4)).cancelIfRequested(EXECUTION);
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
        verify(generator).generate(any(), any());
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
        when(generator.generate(any(), any())).thenThrow(new IllegalStateException(FAILURE_CODE));

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

    private static PlannedCompaction planned() {
        PlanningInput input =
                new PlanningInput(
                        List.of(turn(1, 1, 10), turn(2, 11, 20)),
                        checkpoint(),
                        List.of(),
                        new ContextBudget(1_000, 100, 100, 0),
                        100,
                        900);
        CompactionRequest request = new CompactionRequest(EXECUTION, input, POLICY, 256);
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
                request,
                plan,
                List.of(
                        new PlannedCandidate("left", LEFT, SourceKind.LEAF),
                        new PlannedCandidate("right", RIGHT, SourceKind.LEAF),
                        new PlannedCandidate("parent", PARENT, SourceKind.PARENT)),
                new PlannedCandidate("cold", PARENT, SourceKind.COLD));
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
}
