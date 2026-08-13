package com.datagent.agentv2.context.hierarchical.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
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
import com.datagent.agentv2.context.hierarchical.summary.SummaryGenerationIdentity;
import com.datagent.agentv2.conversation.context.ContextBudget;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort.ContextLifecycleEvent;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextSummaryModelIdentity;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class HierarchicalCompactionPlanningTransactionTest {

    private static final String SESSION_ID = "session-1";
    private static final String JOB_ID = "job-1";
    private static final String LEAF_NODE_ID = "leaf-node-1";
    private static final String SECRET_BODY = "must-not-be-persisted";
    private static final String LEGACY_V1_PLAN_DOCUMENT =
            """
            {
              "schemaVersion": 1,
              "baseCheckpointVersion": 7,
              "baseStrategyGeneration": 5,
              "inputUpToMessageSequence": 20,
              "targetInputTokens": 700,
              "maxSummaryTokens": 256,
              "plan": {
                "completedTurnBoundary": 1,
                "currentIncompleteTurn": null,
                "coldTarget": {"startTurn": 0, "endTurn": 0},
                "warmTarget": {"startTurn": 1, "endTurn": 1},
                "hotTarget": {"startTurn": 0, "endTurn": 0},
                "coldPromotionRanges": [],
                "warmTargetRanges": [{"startTurn":1,"endTurn":1,"startMessageSequence":10,"endMessageSequence":20,"level":0}],
                "frontier": {"nodeIds":[],"ranges":[],"missingRanges":[{"startTurn":1,"endTurn":1,"startMessageSequence":10,"endMessageSequence":20,"level":0}],"estimatedTokenCount":0,"withinBudget":false,"satisfiable":true},
                "hotDegradationRanges": []
              },
              "candidates": [{"nodeId":"leaf-node-1","range":{"startTurn":1,"endTurn":1,"startMessageSequence":10,"endMessageSequence":20,"level":0},"sourceKind":"LEAF","leftChildNodeId":null,"rightChildNodeId":null,"schemaVersion":1,"promptVersion":1,"modelProvider":"provider","modelName":"model"}],
              "coldCandidate": null
            }
            """;
    private static final String LEGACY_V2_PLAN_DOCUMENT =
            LEGACY_V1_PLAN_DOCUMENT.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2");
    private static final HierarchicalContextPolicySnapshot POLICY =
            new HierarchicalContextPolicySnapshot(1, 0.75d, 0.70d, 10, 100, 1, 1);
    private static final HierarchicalContextPolicySnapshot CHANGED_POLICY =
            new HierarchicalContextPolicySnapshot(1, 0.65d, 0.60d, 8, 80, 1, 1);
    private static final ContextBudget BUDGET = new ContextBudget(1_000, 100, 100, 0);
    private static final ContextExecutionRef EXECUTION =
            new ContextExecutionRef("tenant-1", SESSION_ID, "execution-1", "owner-1", 2L, 20L);
    private static final ContextExecutionRef INCOMPATIBLE_EXECUTION =
            new ContextExecutionRef("tenant-1", SESSION_ID, "execution-1", "owner-2", 2L, 20L);
    private static final SummaryRange LEAF_RANGE = new SummaryRange(1, 1, 10, 20, 0);
    private static final SummaryGenerationIdentity IDENTITY =
            new SummaryGenerationIdentity(
                    EXECUTION.tenantId(),
                    SESSION_ID,
                    1,
                    1,
                    new ContextSummaryModelIdentity("provider", "model"));

    @Test
    void plansInRequiresNewTransactionAndPublishesStartedAfterPersistingBodyFreeJob() {
        BinarySummaryTreePlanner planner = mock(BinarySummaryTreePlanner.class);
        HierarchicalSummaryGenerator generator = mock(HierarchicalSummaryGenerator.class);
        HierarchicalCompactionJobRepository jobs = mock(HierarchicalCompactionJobRepository.class);
        IdGenerator ids = mock(IdGenerator.class);
        ContextExecutionControlPort executions = mock(ContextExecutionControlPort.class);
        PlanningInput input = planningInput();
        stubGeneratorIdentity(generator);
        HierarchicalCompactionPlan plan = plan();
        when(planner.plan(input, POLICY)).thenReturn(plan);
        when(ids.next("hierarchical_compaction_job")).thenReturn(JOB_ID);
        when(generator.nodeKey(
                        EXECUTION.tenantId(),
                        SESSION_ID,
                        generationRange(LEAF_RANGE),
                        SourceKind.LEAF,
                        POLICY))
                .thenReturn(LEAF_NODE_ID);
        when(jobs.insertOrSelect(any())).thenAnswer(invocation -> invocation.getArgument(0));
        HierarchicalCompactionPlanningTransaction transaction =
                new HierarchicalCompactionPlanningTransaction(
                        planner, generator, jobs, ids, executions, JsonMapper.builder().build());

        HierarchicalCompactionPlanningTransaction.PlannedCompaction planned =
                transaction.plan(compactionRequest(), input);

        ArgumentCaptor<HierarchicalCompactionJob> job =
                ArgumentCaptor.forClass(HierarchicalCompactionJob.class);
        verify(jobs).insertOrSelect(job.capture());
        assertThat(job.getValue().status()).isEqualTo(HierarchicalCompactionJob.Status.PLANNED);
        assertThat(job.getValue().baseCheckpointVersion()).isEqualTo(7L);
        assertThat(job.getValue().baseStrategyGeneration()).isEqualTo(5L);
        assertThat(job.getValue().inputUpToMessageSequence()).isEqualTo(20L);
        assertThat(job.getValue().planDocument())
                .contains(
                        LEAF_NODE_ID,
                        "\"schemaVersion\":3",
                        "\"targetInputTokens\":700",
                        "\"warmTokenBudget\":600")
                .doesNotContain(SECRET_BODY);
        assertThat(planned.candidates())
                .extracting(candidate -> candidate.nodeId())
                .containsExactly(LEAF_NODE_ID);

        InOrder order = inOrder(executions, planner, jobs);
        order.verify(executions).assertOwnedAndWithinBudget(EXECUTION);
        order.verify(planner).plan(input, POLICY);
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
                                HierarchicalCompactionPlanningTransaction.CompactionRequest.class,
                                PlanningInput.class)
                        .getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void priorV1PlanFailsWithStableReplanRequiredOutcome() {
        assertPersistedPlanRejected(
                LEGACY_V1_PLAN_DOCUMENT,
                "Hierarchical plan version 1 is unsupported; replan required");
    }

    @Test
    void priorV2PlanFailsWithStableReplanRequiredOutcome() {
        assertPersistedPlanRejected(
                LEGACY_V2_PLAN_DOCUMENT,
                "Hierarchical plan version 2 is unsupported; replan required");
    }

    @Test
    void unknownFuturePlanVersionIsRejectedExplicitly() {
        assertPersistedPlanRejected(
                LEGACY_V1_PLAN_DOCUMENT.replace("\"schemaVersion\": 1", "\"schemaVersion\": 4"),
                "Unsupported hierarchical plan schema version: 4");
    }

    @Test
    void existingParentCandidateIsReusedWithoutPlanningMissingDescendants() {
        BinarySummaryTreePlanner planner = mock(BinarySummaryTreePlanner.class);
        HierarchicalSummaryGenerator generator = mock(HierarchicalSummaryGenerator.class);
        HierarchicalCompactionJobRepository jobs = mock(HierarchicalCompactionJobRepository.class);
        IdGenerator ids = mock(IdGenerator.class);
        ContextExecutionControlPort executions = mock(ContextExecutionControlPort.class);
        SummaryRange parentRange = new SummaryRange(1, 2, 10, 30, 1);
        stubGeneratorIdentity(generator);
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
        when(planner.plan(input, POLICY)).thenReturn(plan);
        when(ids.next("hierarchical_compaction_job")).thenReturn(JOB_ID);
        GenerationRange coldRange = new GenerationRange(1, 2, 10, 30, 0);
        when(generator.nodeKey(
                        EXECUTION.tenantId(), SESSION_ID, coldRange, SourceKind.COLD, POLICY))
                .thenReturn("cold-node");
        when(jobs.insertOrSelect(any())).thenAnswer(invocation -> invocation.getArgument(0));
        HierarchicalCompactionPlanningTransaction transaction =
                new HierarchicalCompactionPlanningTransaction(
                        planner, generator, jobs, ids, executions, JsonMapper.builder().build());

        HierarchicalCompactionPlanningTransaction.PlannedCompaction planned =
                transaction.plan(compactionRequest(), input);

        assertThat(planned.candidates())
                .extracting(candidate -> candidate.nodeId())
                .containsExactly("existing-parent");
        verify(generator)
                .nodeKey(EXECUTION.tenantId(), SESSION_ID, coldRange, SourceKind.COLD, POLICY);
        verify(generator).generationIdentity(EXECUTION.tenantId(), SESSION_ID, POLICY);
        verifyNoMoreInteractions(generator);
    }

    @Test
    void selectedExistingFrontierIsPersistedAsAValidatedBodyFreeDescriptor() {
        BinarySummaryTreePlanner planner = mock(BinarySummaryTreePlanner.class);
        HierarchicalSummaryGenerator generator = mock(HierarchicalSummaryGenerator.class);
        HierarchicalCompactionJobRepository jobs = mock(HierarchicalCompactionJobRepository.class);
        IdGenerator ids = mock(IdGenerator.class);
        ContextExecutionControlPort executions = mock(ContextExecutionControlPort.class);
        SummaryRange parentRange = new SummaryRange(1, 2, 10, 30, 1);
        com.datagent.agentv2.context.hierarchical.domain.HierarchicalSummaryNode parent =
                new com.datagent.agentv2.context.hierarchical.domain.HierarchicalSummaryNode(
                        "frontier-parent",
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
        HierarchicalCompactionPlan selectedFrontier =
                new HierarchicalCompactionPlan(
                        2,
                        Optional.empty(),
                        TurnRange.EMPTY,
                        new TurnRange(1, 2),
                        TurnRange.EMPTY,
                        List.of(),
                        List.of(parentRange),
                        new FrontierSelection(
                                List.of("frontier-parent"),
                                List.of(parentRange),
                                List.of(),
                                20,
                                true,
                                true),
                        List.of());
        stubGeneratorIdentity(generator);
        when(planner.plan(input, POLICY)).thenReturn(selectedFrontier);
        when(ids.next("hierarchical_compaction_job")).thenReturn(JOB_ID);
        when(jobs.insertOrSelect(any())).thenAnswer(invocation -> invocation.getArgument(0));
        HierarchicalCompactionPlanningTransaction transaction =
                new HierarchicalCompactionPlanningTransaction(
                        planner, generator, jobs, ids, executions, JsonMapper.builder().build());

        HierarchicalCompactionPlanningTransaction.PlannedCompaction planned =
                transaction.plan(compactionRequest(), input);

        assertThat(planned.candidates())
                .singleElement()
                .satisfies(
                        descriptor -> {
                            assertThat(descriptor.nodeId()).isEqualTo("frontier-parent");
                            assertThat(descriptor.range()).isEqualTo(generationRange(parentRange));
                            assertThat(descriptor.leftChildNodeId()).isEqualTo("left");
                            assertThat(descriptor.rightChildNodeId()).isEqualTo("right");
                        });
    }

    @Test
    void boundaryConflictReturnsTheExistingJobWithoutPublishingDuplicateStarted() {
        BinarySummaryTreePlanner planner = mock(BinarySummaryTreePlanner.class);
        HierarchicalSummaryGenerator generator = mock(HierarchicalSummaryGenerator.class);
        HierarchicalCompactionJobRepository jobs = mock(HierarchicalCompactionJobRepository.class);
        IdGenerator ids = mock(IdGenerator.class);
        ContextExecutionControlPort executions = mock(ContextExecutionControlPort.class);
        PlanningInput input = planningInput();
        stubGeneratorIdentity(generator);
        when(planner.plan(input, POLICY)).thenReturn(plan());
        when(ids.next("hierarchical_compaction_job")).thenReturn("existing-job", "new-job");
        when(generator.nodeKey(
                        EXECUTION.tenantId(),
                        SESSION_ID,
                        generationRange(LEAF_RANGE),
                        SourceKind.LEAF,
                        POLICY))
                .thenReturn(LEAF_NODE_ID);
        AtomicReference<HierarchicalCompactionJob> existing = new AtomicReference<>();
        when(jobs.insertOrSelect(any()))
                .thenAnswer(
                        invocation -> {
                            if (existing.get() == null) {
                                existing.set(invocation.getArgument(0));
                            }
                            return existing.get();
                        });
        HierarchicalCompactionPlanningTransaction transaction =
                new HierarchicalCompactionPlanningTransaction(
                        planner, generator, jobs, ids, executions, JsonMapper.builder().build());

        HierarchicalCompactionPlanningTransaction.PlannedCompaction first =
                transaction.plan(compactionRequest(), input);
        HierarchicalCompactionPlanningTransaction.PlannedCompaction repeated =
                transaction.plan(compactionRequest(), input);

        assertThat(repeated.job()).isEqualTo(first.job());
        verify(executions).publish(any(), any());
    }

    @Test
    void conflictUsesPersistedDurablePlanInsteadOfTheNewlyRecomputedPlan() {
        BinarySummaryTreePlanner planner = mock(BinarySummaryTreePlanner.class);
        HierarchicalSummaryGenerator generator = mock(HierarchicalSummaryGenerator.class);
        HierarchicalCompactionJobRepository jobs = mock(HierarchicalCompactionJobRepository.class);
        IdGenerator ids = mock(IdGenerator.class);
        ContextExecutionControlPort executions = mock(ContextExecutionControlPort.class);
        PlanningInput input = twoTurnPlanningInput(List.of());
        stubGeneratorIdentity(generator);
        HierarchicalCompactionPlan oldPlan = plan();
        SummaryRange newerRange = new SummaryRange(1, 2, 10, 30, 1);
        HierarchicalCompactionPlan recomputed =
                new HierarchicalCompactionPlan(
                        2,
                        Optional.empty(),
                        TurnRange.EMPTY,
                        new TurnRange(1, 2),
                        TurnRange.EMPTY,
                        List.of(),
                        List.of(newerRange),
                        new FrontierSelection(
                                List.of(), List.of(), List.of(newerRange), 0, false, true),
                        List.of());
        when(ids.next("hierarchical_compaction_job")).thenReturn("old-job", "new-job");
        when(generator.nodeKey(
                        EXECUTION.tenantId(),
                        SESSION_ID,
                        generationRange(LEAF_RANGE),
                        SourceKind.LEAF,
                        POLICY))
                .thenReturn(LEAF_NODE_ID);
        when(generator.nodeKey(
                        org.mockito.ArgumentMatchers.eq(EXECUTION.tenantId()),
                        org.mockito.ArgumentMatchers.eq(SESSION_ID),
                        any(GenerationRange.class),
                        any(SourceKind.class),
                        any(HierarchicalContextPolicySnapshot.class)))
                .thenAnswer(
                        invocation ->
                                invocation.<SourceKind>getArgument(3).name().toLowerCase()
                                        + "-"
                                        + invocation.<GenerationRange>getArgument(2).level());
        AtomicReference<HierarchicalCompactionJob> persisted = new AtomicReference<>();
        when(jobs.insertOrSelect(any()))
                .thenAnswer(
                        invocation -> {
                            HierarchicalCompactionJob candidate = invocation.getArgument(0);
                            if (persisted.get() == null) {
                                persisted.set(candidate);
                            }
                            return persisted.get();
                        });
        when(planner.plan(input, POLICY)).thenReturn(oldPlan);
        when(planner.plan(input, CHANGED_POLICY)).thenReturn(recomputed);
        when(generator.generationIdentity(EXECUTION.tenantId(), SESSION_ID, CHANGED_POLICY))
                .thenReturn(IDENTITY);
        HierarchicalCompactionPlanningTransaction transaction =
                new HierarchicalCompactionPlanningTransaction(
                        planner, generator, jobs, ids, executions, JsonMapper.builder().build());

        HierarchicalCompactionPlanningTransaction.PlannedCompaction first =
                transaction.plan(compactionRequest(), input);
        HierarchicalCompactionPlanningTransaction.CompactionRequest changedRequest =
                new HierarchicalCompactionPlanningTransaction.CompactionRequest(
                        EXECUTION, CHANGED_POLICY, 128);
        HierarchicalCompactionPlanningTransaction.PlannedCompaction conflicted =
                transaction.plan(changedRequest, input);

        assertThat(first.candidates()).isEqualTo(conflicted.candidates());
        assertThat(conflicted.plan()).isEqualTo(oldPlan);
        assertThat(conflicted.generationSettings().policySnapshot()).isEqualTo(POLICY);
        assertThat(conflicted.generationSettings().maxSummaryTokens()).isEqualTo(256);
        assertThat(conflicted.candidates())
                .extracting(candidate -> candidate.range())
                .containsExactly(generationRange(LEAF_RANGE));
    }

    @Test
    void conflictRejectsPersistedJobOwnedByADifferentExecutionBoundary() {
        BinarySummaryTreePlanner planner = mock(BinarySummaryTreePlanner.class);
        HierarchicalSummaryGenerator generator = mock(HierarchicalSummaryGenerator.class);
        HierarchicalCompactionJobRepository jobs = mock(HierarchicalCompactionJobRepository.class);
        IdGenerator ids = mock(IdGenerator.class);
        ContextExecutionControlPort executions = mock(ContextExecutionControlPort.class);
        PlanningInput input = planningInput();
        stubGeneratorIdentity(generator);
        when(planner.plan(input, POLICY)).thenReturn(plan());
        when(ids.next("hierarchical_compaction_job")).thenReturn("existing-job", "conflicting-job");
        when(generator.nodeKey(
                        EXECUTION.tenantId(),
                        SESSION_ID,
                        generationRange(LEAF_RANGE),
                        SourceKind.LEAF,
                        POLICY))
                .thenReturn(LEAF_NODE_ID);
        AtomicReference<HierarchicalCompactionJob> persisted = new AtomicReference<>();
        when(jobs.insertOrSelect(any()))
                .thenAnswer(
                        invocation -> {
                            if (persisted.get() == null) {
                                persisted.set(invocation.getArgument(0));
                            }
                            return persisted.get();
                        });
        HierarchicalCompactionPlanningTransaction transaction =
                new HierarchicalCompactionPlanningTransaction(
                        planner, generator, jobs, ids, executions, JsonMapper.builder().build());
        transaction.plan(compactionRequest(), input);
        HierarchicalCompactionPlanningTransaction.CompactionRequest incompatible =
                new HierarchicalCompactionPlanningTransaction.CompactionRequest(
                        INCOMPATIBLE_EXECUTION, POLICY, 256);

        assertThatThrownBy(() -> transaction.plan(incompatible, input))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incompatible");
    }

    @Test
    void springProxySuspendsOuterTransactionAndRollsBackOnlyFailingPlanningTransaction() {
        BinarySummaryTreePlanner planner = mock(BinarySummaryTreePlanner.class);
        HierarchicalSummaryGenerator generator = mock(HierarchicalSummaryGenerator.class);
        HierarchicalCompactionJobRepository jobs = mock(HierarchicalCompactionJobRepository.class);
        IdGenerator ids = mock(IdGenerator.class);
        ContextExecutionControlPort executions = mock(ContextExecutionControlPort.class);
        PlanningInput input = planningInput();
        stubGeneratorIdentity(generator);
        when(planner.plan(input, POLICY)).thenReturn(plan());
        when(ids.next("hierarchical_compaction_job")).thenReturn(JOB_ID);
        when(generator.nodeKey(
                        EXECUTION.tenantId(),
                        SESSION_ID,
                        generationRange(LEAF_RANGE),
                        SourceKind.LEAF,
                        POLICY))
                .thenReturn(LEAF_NODE_ID);
        AtomicReference<String> innerTransaction = new AtomicReference<>();
        when(jobs.insertOrSelect(any()))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isTrue();
                            innerTransaction.set(RecordingTransactionManager.currentId());
                            return invocation.getArgument(0);
                        });
        org.mockito.Mockito.doThrow(new IllegalStateException("publish failed"))
                .when(executions)
                .publish(any(), any());

        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.register(TransactionTestConfiguration.class);
            context.registerBean(BinarySummaryTreePlanner.class, () -> planner);
            context.registerBean(HierarchicalSummaryGenerator.class, () -> generator);
            context.registerBean(HierarchicalCompactionJobRepository.class, () -> jobs);
            context.registerBean(IdGenerator.class, () -> ids);
            context.registerBean(ContextExecutionControlPort.class, () -> executions);
            context.registerBean(
                    HierarchicalCompactionPlanningTransaction.class,
                    () ->
                            new HierarchicalCompactionPlanningTransaction(
                                    planner,
                                    generator,
                                    jobs,
                                    ids,
                                    executions,
                                    JsonMapper.builder().build()));
            context.refresh();
            RecordingTransactionManager transactions =
                    context.getBean(RecordingTransactionManager.class);
            HierarchicalCompactionPlanningTransaction proxy =
                    context.getBean(HierarchicalCompactionPlanningTransaction.class);
            AtomicReference<String> outerTransaction = new AtomicReference<>();

            new TransactionTemplate(transactions)
                    .executeWithoutResult(
                            ignored -> {
                                outerTransaction.set(RecordingTransactionManager.currentId());
                                assertThatThrownBy(() -> proxy.plan(compactionRequest(), input))
                                        .isInstanceOf(IllegalStateException.class)
                                        .hasMessage("publish failed");
                                assertThat(RecordingTransactionManager.currentId())
                                        .isEqualTo(outerTransaction.get());
                            });

            assertThat(innerTransaction.get()).isNotEqualTo(outerTransaction.get());
            assertThat(transactions.committedIds()).containsExactly(outerTransaction.get());
            assertThat(transactions.rolledBackIds()).containsExactly(innerTransaction.get());
        }
    }

    private static HierarchicalCompactionPlanningTransaction.CompactionRequest compactionRequest() {
        return new HierarchicalCompactionPlanningTransaction.CompactionRequest(
                EXECUTION, POLICY, 256);
    }

    private static void assertPersistedPlanRejected(String planDocument, String expectedMessage) {
        BinarySummaryTreePlanner planner = mock(BinarySummaryTreePlanner.class);
        HierarchicalSummaryGenerator generator = mock(HierarchicalSummaryGenerator.class);
        HierarchicalCompactionJobRepository jobs = mock(HierarchicalCompactionJobRepository.class);
        IdGenerator ids = mock(IdGenerator.class);
        ContextExecutionControlPort executions = mock(ContextExecutionControlPort.class);
        PlanningInput input = planningInput();
        stubGeneratorIdentity(generator);
        when(planner.plan(input, POLICY)).thenReturn(plan());
        when(ids.next("hierarchical_compaction_job")).thenReturn(JOB_ID);
        when(generator.nodeKey(
                        EXECUTION.tenantId(),
                        SESSION_ID,
                        generationRange(LEAF_RANGE),
                        SourceKind.LEAF,
                        POLICY))
                .thenReturn(LEAF_NODE_ID);
        when(jobs.insertOrSelect(any()))
                .thenReturn(
                        new HierarchicalCompactionJob(
                                "persisted-job",
                                SESSION_ID,
                                EXECUTION.executionId(),
                                EXECUTION.executionAttempt(),
                                EXECUTION.ownerInstanceId(),
                                EXECUTION.inputUpToMessageSequence(),
                                7L,
                                5L,
                                planDocument,
                                HierarchicalCompactionJob.Status.PLANNED,
                                null,
                                0L));
        HierarchicalCompactionPlanningTransaction transaction =
                new HierarchicalCompactionPlanningTransaction(
                        planner, generator, jobs, ids, executions, JsonMapper.builder().build());

        assertThatThrownBy(() -> transaction.plan(compactionRequest(), input))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(expectedMessage);
    }

    private static void stubGeneratorIdentity(HierarchicalSummaryGenerator generator) {
        when(generator.generationIdentity(EXECUTION.tenantId(), SESSION_ID, POLICY))
                .thenReturn(IDENTITY);
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

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionTestConfiguration {

        @org.springframework.context.annotation.Bean
        RecordingTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }
    }

    static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
        private final java.util.concurrent.atomic.AtomicInteger sequence =
                new java.util.concurrent.atomic.AtomicInteger();
        private final List<String> committed = new java.util.ArrayList<>();
        private final List<String> rolledBack = new java.util.ArrayList<>();

        static String currentId() {
            return CURRENT.get();
        }

        List<String> committedIds() {
            return List.copyOf(committed);
        }

        List<String> rolledBackIds() {
            return List.copyOf(rolledBack);
        }

        @Override
        protected Object doGetTransaction() {
            return new RecordingTransaction(CURRENT.get());
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return ((RecordingTransaction) transaction).existingId() != null;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            CURRENT.set("tx-" + sequence.incrementAndGet());
        }

        @Override
        protected Object doSuspend(Object transaction) {
            String suspended = CURRENT.get();
            CURRENT.remove();
            return suspended;
        }

        @Override
        protected void doResume(Object transaction, Object suspendedResources) {
            CURRENT.set((String) suspendedResources);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            committed.add(CURRENT.get());
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rolledBack.add(CURRENT.get());
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            CURRENT.remove();
        }
    }

    private record RecordingTransaction(String existingId) {}
}
