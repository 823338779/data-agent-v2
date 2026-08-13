package com.datagent.agentv2.context.hierarchical.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.context.hierarchical.domain.FrontierSelection;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionJob;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionPlan;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionPlan.TurnRange;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.domain.SummaryRange;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCompactionJobRepository;
import com.datagent.agentv2.context.hierarchical.planning.BinarySummaryTreePlanner;
import com.datagent.agentv2.context.hierarchical.planning.BinarySummaryTreePlanner.PlanningInput;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GenerationRange;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.SourceKind;
import com.datagent.agentv2.conversation.context.ContextBudget;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort.ContextLifecycleEvent;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

class HierarchicalCompactionPlanningTransactionTest {

    private static final String SESSION_ID = "session-1";
    private static final String JOB_ID = "job-1";
    private static final String LEAF_NODE_ID = "leaf-node-1";
    private static final String SECRET_BODY = "must-not-be-persisted";
    private static final HierarchicalContextPolicySnapshot POLICY =
            new HierarchicalContextPolicySnapshot(1, 0.75d, 0.70d, 10, 100, 1, 1);
    private static final ContextBudget BUDGET = new ContextBudget(1_000, 100, 100, 0);
    private static final ContextExecutionRef EXECUTION =
            new ContextExecutionRef("tenant-1", SESSION_ID, "execution-1", "owner-1", 2L, 20L);
    private static final SummaryRange LEAF_RANGE = new SummaryRange(1, 1, 10, 20, 0);

    @Test
    void plansInRequiresNewTransactionAndPublishesStartedAfterPersistingBodyFreeJob() {
        BinarySummaryTreePlanner planner = mock(BinarySummaryTreePlanner.class);
        HierarchicalSummaryGenerator generator = mock(HierarchicalSummaryGenerator.class);
        HierarchicalCompactionJobRepository jobs = mock(HierarchicalCompactionJobRepository.class);
        IdGenerator ids = mock(IdGenerator.class);
        ContextExecutionControlPort executions = mock(ContextExecutionControlPort.class);
        PlanningInput input = planningInput();
        HierarchicalCompactionPlan plan = plan();
        when(planner.plan(input)).thenReturn(plan);
        when(ids.next("hierarchical_compaction_job")).thenReturn(JOB_ID);
        when(generator.nodeKey(SESSION_ID, generationRange(LEAF_RANGE), SourceKind.LEAF, POLICY))
                .thenReturn(LEAF_NODE_ID);
        when(jobs.insertOrSelect(any())).thenAnswer(invocation -> invocation.getArgument(0));
        HierarchicalCompactionPlanningTransaction transaction =
                new HierarchicalCompactionPlanningTransaction(
                        planner, generator, jobs, ids, executions, JsonMapper.builder().build());

        HierarchicalCompactionPlanningTransaction.PlannedCompaction planned =
                transaction.plan(
                        new HierarchicalCompactionPlanningTransaction.CompactionRequest(
                                EXECUTION, input, POLICY, 256));

        ArgumentCaptor<HierarchicalCompactionJob> job =
                ArgumentCaptor.forClass(HierarchicalCompactionJob.class);
        verify(jobs).insertOrSelect(job.capture());
        assertThat(job.getValue().status()).isEqualTo(HierarchicalCompactionJob.Status.PLANNED);
        assertThat(job.getValue().baseCheckpointVersion()).isEqualTo(7L);
        assertThat(job.getValue().baseStrategyGeneration()).isEqualTo(5L);
        assertThat(job.getValue().inputUpToMessageSequence()).isEqualTo(20L);
        assertThat(job.getValue().planDocument())
                .contains(LEAF_NODE_ID, "\"targetInputTokens\":700")
                .doesNotContain(SECRET_BODY);
        assertThat(planned.candidates())
                .extracting(candidate -> candidate.nodeId())
                .containsExactly(LEAF_NODE_ID);

        InOrder order = inOrder(executions, planner, jobs);
        order.verify(executions).assertOwnedAndWithinBudget(EXECUTION);
        order.verify(planner).plan(input);
        order.verify(jobs).insertOrSelect(any());
        ArgumentCaptor<ContextLifecycleEvent> event =
                ArgumentCaptor.forClass(ContextLifecycleEvent.class);
        order.verify(executions)
                .publish(org.mockito.ArgumentMatchers.eq(EXECUTION), event.capture());
        assertThat(event.getValue().type()).isEqualTo(ContextLifecycleEvent.Type.STARTED);
        assertThat(event.getValue().metadata())
                .containsEntry("compactionJobId", JOB_ID)
                .containsEntry("inputUpToMessageSequence", 20L);
    }

    @Test
    void planMethodRequiresANewTransaction() throws Exception {
        Transactional annotation =
                HierarchicalCompactionPlanningTransaction.class
                        .getMethod(
                                "plan",
                                HierarchicalCompactionPlanningTransaction.CompactionRequest.class)
                        .getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void existingParentCandidateIsReusedWithoutPlanningMissingDescendants() {
        BinarySummaryTreePlanner planner = mock(BinarySummaryTreePlanner.class);
        HierarchicalSummaryGenerator generator = mock(HierarchicalSummaryGenerator.class);
        HierarchicalCompactionJobRepository jobs = mock(HierarchicalCompactionJobRepository.class);
        IdGenerator ids = mock(IdGenerator.class);
        ContextExecutionControlPort executions = mock(ContextExecutionControlPort.class);
        SummaryRange parentRange = new SummaryRange(1, 2, 10, 30, 1);
        com.datagent.agentv2.context.hierarchical.domain.HierarchicalSummaryNode parent =
                new com.datagent.agentv2.context.hierarchical.domain.HierarchicalSummaryNode(
                        "existing-parent",
                        SESSION_ID,
                        1,
                        2,
                        10,
                        30,
                        "left",
                        "right",
                        "{\"schemaVersion\":1}",
                        20,
                        1,
                        1,
                        "provider",
                        "model");
        PlanningInput input = twoTurnPlanningInput(List.of(parent));
        HierarchicalCompactionPlan plan =
                new HierarchicalCompactionPlan(
                        2,
                        Optional.empty(),
                        new TurnRange(1, 2),
                        TurnRange.EMPTY,
                        TurnRange.EMPTY,
                        List.of(parentRange),
                        List.of(),
                        FrontierSelection.empty(),
                        List.of());
        when(planner.plan(input)).thenReturn(plan);
        when(ids.next("hierarchical_compaction_job")).thenReturn(JOB_ID);
        GenerationRange coldRange = new GenerationRange(1, 2, 10, 30, 0);
        when(generator.nodeKey(SESSION_ID, coldRange, SourceKind.COLD, POLICY))
                .thenReturn("cold-node");
        when(jobs.insertOrSelect(any())).thenAnswer(invocation -> invocation.getArgument(0));
        HierarchicalCompactionPlanningTransaction transaction =
                new HierarchicalCompactionPlanningTransaction(
                        planner, generator, jobs, ids, executions, JsonMapper.builder().build());

        HierarchicalCompactionPlanningTransaction.PlannedCompaction planned =
                transaction.plan(
                        new HierarchicalCompactionPlanningTransaction.CompactionRequest(
                                EXECUTION, input, POLICY, 256));

        assertThat(planned.candidates())
                .extracting(candidate -> candidate.nodeId())
                .containsExactly("existing-parent");
        verify(generator).nodeKey(SESSION_ID, coldRange, SourceKind.COLD, POLICY);
        verifyNoMoreInteractions(generator);
    }

    @Test
    void boundaryConflictReturnsTheExistingJobWithoutPublishingDuplicateStarted() {
        BinarySummaryTreePlanner planner = mock(BinarySummaryTreePlanner.class);
        HierarchicalSummaryGenerator generator = mock(HierarchicalSummaryGenerator.class);
        HierarchicalCompactionJobRepository jobs = mock(HierarchicalCompactionJobRepository.class);
        IdGenerator ids = mock(IdGenerator.class);
        ContextExecutionControlPort executions = mock(ContextExecutionControlPort.class);
        PlanningInput input = planningInput();
        when(planner.plan(input)).thenReturn(plan());
        when(ids.next("hierarchical_compaction_job")).thenReturn("new-job");
        when(generator.nodeKey(SESSION_ID, generationRange(LEAF_RANGE), SourceKind.LEAF, POLICY))
                .thenReturn(LEAF_NODE_ID);
        HierarchicalCompactionJob existing =
                new HierarchicalCompactionJob(
                        "existing-job",
                        SESSION_ID,
                        EXECUTION.executionId(),
                        EXECUTION.executionAttempt(),
                        EXECUTION.ownerInstanceId(),
                        EXECUTION.inputUpToMessageSequence(),
                        7,
                        5,
                        "{\"schemaVersion\":1}",
                        HierarchicalCompactionJob.Status.PLANNED,
                        null,
                        0);
        when(jobs.insertOrSelect(any())).thenReturn(existing);
        HierarchicalCompactionPlanningTransaction transaction =
                new HierarchicalCompactionPlanningTransaction(
                        planner, generator, jobs, ids, executions, JsonMapper.builder().build());

        HierarchicalCompactionPlanningTransaction.PlannedCompaction planned =
                transaction.plan(
                        new HierarchicalCompactionPlanningTransaction.CompactionRequest(
                                EXECUTION, input, POLICY, 256));

        assertThat(planned.job()).isEqualTo(existing);
        verify(executions, never()).publish(any(), any());
    }

    private static PlanningInput planningInput() {
        ConversationTurn turn =
                new ConversationTurn(1, 10, 20, true, List.of(ContextMessage.user(SECRET_BODY)));
        HierarchicalCheckpoint checkpoint =
                new HierarchicalCheckpoint(
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
                        5L,
                        7L);
        return new PlanningInput(List.of(turn), checkpoint, List.of(), BUDGET, 100, 900);
    }

    private static PlanningInput twoTurnPlanningInput(
            List<com.datagent.agentv2.context.hierarchical.domain.HierarchicalSummaryNode> nodes) {
        List<ConversationTurn> turns =
                List.of(
                        new ConversationTurn(
                                1, 10, 20, true, List.of(ContextMessage.user("turn-1"))),
                        new ConversationTurn(
                                2, 21, 30, true, List.of(ContextMessage.user("turn-2"))));
        HierarchicalCheckpoint checkpoint =
                new HierarchicalCheckpoint(
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
                        5L,
                        7L);
        return new PlanningInput(turns, checkpoint, nodes, BUDGET, 100, 900);
    }

    private static HierarchicalCompactionPlan plan() {
        return new HierarchicalCompactionPlan(
                1,
                Optional.empty(),
                TurnRange.EMPTY,
                new TurnRange(1, 1),
                TurnRange.EMPTY,
                List.of(),
                List.of(LEAF_RANGE),
                new FrontierSelection(List.of(), List.of(), List.of(LEAF_RANGE), 0, false, true),
                List.of());
    }

    private static GenerationRange generationRange(SummaryRange range) {
        return new GenerationRange(
                range.startTurn(),
                range.endTurn(),
                range.startMessageSequence(),
                range.endMessageSequence(),
                range.level());
    }
}
