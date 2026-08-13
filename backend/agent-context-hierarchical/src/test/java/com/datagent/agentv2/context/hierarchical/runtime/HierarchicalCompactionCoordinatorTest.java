package com.datagent.agentv2.context.hierarchical.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.hierarchical.domain.FrontierSelection;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionJob;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionPlan;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionPlan.TurnRange;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextState;
import com.datagent.agentv2.context.hierarchical.planning.BinarySummaryTreePlanner.PlanningInput;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCandidateGenerationService.ReadyCompaction;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCheckpointPublicationTransaction.PublishResult;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionCoordinator.CompactionOutcome;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction.CompactionRequest;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction.GenerationSettings;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction.PlannedCompaction;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionRecoveryService.RecoveryResult;
import com.datagent.agentv2.conversation.context.ContextBudget;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import com.datagent.agentv2.conversation.context.PreparedConversationContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class HierarchicalCompactionCoordinatorTest {

    private static final String SESSION_ID = "session-coordinator";
    private static final String JOB_ID = "job-coordinator";
    private static final int MAX_SUMMARY_TOKENS = 512;
    private static final HierarchicalContextPolicySnapshot POLICY =
            new HierarchicalContextPolicySnapshot(1, 0.75d, 0.70d, 10, 100, 1, 1);
    private static final ContextBudget BUDGET = new ContextBudget(1_000, 100, 100, 0);
    private static final ContextExecutionRef EXECUTION =
            new ContextExecutionRef(
                    "tenant-coordinator",
                    SESSION_ID,
                    "execution-coordinator",
                    "owner-coordinator",
                    1L,
                    20L);

    private HierarchicalCompactionPlanningTransaction planning;
    private HierarchicalCandidateGenerationService generation;
    private HierarchicalCompactionRecoveryService recovery;
    private HierarchicalCheckpointPublicationTransaction publication;
    private HierarchicalConversationContextAssembler assembler;
    private com.datagent.agentv2.context.hierarchical.persistence.repository
                    .HierarchicalCheckpointRepository
            checkpoints;
    private ContextExecutionControlPort executions;
    private HierarchicalCompactionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        planning = mock(HierarchicalCompactionPlanningTransaction.class);
        generation = mock(HierarchicalCandidateGenerationService.class);
        recovery = mock(HierarchicalCompactionRecoveryService.class);
        publication = mock(HierarchicalCheckpointPublicationTransaction.class);
        assembler = mock(HierarchicalConversationContextAssembler.class);
        checkpoints =
                mock(
                        com.datagent.agentv2.context.hierarchical.persistence.repository
                                .HierarchicalCheckpointRepository.class);
        executions = mock(ContextExecutionControlPort.class);
        coordinator =
                new HierarchicalCompactionCoordinator(
                        planning,
                        generation,
                        recovery,
                        publication,
                        assembler,
                        checkpoints,
                        executions,
                        MAX_SUMMARY_TOKENS);
    }

    @Test
    void plansGeneratesPublishesAndVerifiesThePublishedBudget() {
        HierarchicalContextState state = state(checkpoint(5L, 0L), 760);
        HierarchicalConversationContextAssembler.CompactionInput input = compactionInput(state);
        PlannedCompaction planned = planned(state);
        ReadyCompaction ready = new ReadyCompaction(planned, false);
        HierarchicalCheckpoint published = checkpoint(6L, 1L);
        when(recovery.recover(EXECUTION, state.checkpoint())).thenReturn(RecoveryResult.none());
        when(assembler.toCompactionInput(state)).thenReturn(input);
        when(planning.plan(any(CompactionRequest.class), any(PlanningInput.class)))
                .thenReturn(planned);
        when(generation.generate(planned)).thenReturn(ready);
        when(publication.publish(ready, state.request(), state.policy()))
                .thenReturn(PublishResult.PUBLISHED);
        when(checkpoints.findCheckpoint(SESSION_ID)).thenReturn(published);
        when(assembler.preparePublishedView(published, state.request(), POLICY))
                .thenReturn(
                        new PreparedConversationContext("hierarchical", List.of(), 690, true, 6L));

        CompactionOutcome outcome = coordinator.compactIfRequired(state);

        assertThat(outcome.checkpoint()).isEqualTo(published);
        assertThat(outcome.targetReached()).isTrue();
        assertThat(outcome.withinHardLimit()).isTrue();
        InOrder order = inOrder(recovery, planning, generation, publication, assembler);
        order.verify(recovery).recover(EXECUTION, state.checkpoint());
        order.verify(assembler).toCompactionInput(state);
        order.verify(planning).plan(any(), any());
        order.verify(generation).generate(planned);
        order.verify(publication).publish(ready, state.request(), state.policy());
        order.verify(assembler).preparePublishedView(published, state.request(), POLICY);
    }

    @Test
    void stalePublicationReloadsNewestCheckpointBeforeReplanning() {
        HierarchicalContextState first = state(checkpoint(5L, 0L), 760);
        HierarchicalContextState refreshed = state(checkpoint(6L, 1L), 750);
        PlannedCompaction firstPlan = planned(first);
        PlannedCompaction secondPlan = planned(refreshed);
        ReadyCompaction firstReady = new ReadyCompaction(firstPlan, false);
        ReadyCompaction secondReady = new ReadyCompaction(secondPlan, false);
        HierarchicalCheckpoint published = checkpoint(7L, 2L);
        when(recovery.recover(EXECUTION, first.checkpoint())).thenReturn(RecoveryResult.none());
        when(recovery.recover(EXECUTION, refreshed.checkpoint())).thenReturn(RecoveryResult.none());
        when(assembler.toCompactionInput(first)).thenReturn(compactionInput(first));
        when(assembler.toCompactionInput(refreshed)).thenReturn(compactionInput(refreshed));
        when(planning.plan(any(), any())).thenReturn(firstPlan, secondPlan);
        when(generation.generate(firstPlan)).thenReturn(firstReady);
        when(generation.generate(secondPlan)).thenReturn(secondReady);
        when(publication.publish(firstReady, first.request(), first.policy()))
                .thenReturn(PublishResult.STALE);
        when(publication.publish(secondReady, refreshed.request(), refreshed.policy()))
                .thenReturn(PublishResult.PUBLISHED);
        when(assembler.loadAndEstimate(first.request(), POLICY)).thenReturn(refreshed);
        when(checkpoints.findCheckpoint(SESSION_ID)).thenReturn(published);
        when(assembler.preparePublishedView(published, first.request(), POLICY))
                .thenReturn(
                        new PreparedConversationContext("hierarchical", List.of(), 710, true, 7L));

        CompactionOutcome outcome = coordinator.compactIfRequired(first);

        assertThat(outcome.checkpoint()).isEqualTo(published);
        verify(assembler).loadAndEstimate(first.request(), POLICY);
        verify(recovery).recover(EXECUTION, refreshed.checkpoint());
    }

    @Test
    void protectedMinimumMayMissTargetButMustRemainWithinHardLimit() {
        HierarchicalContextState state = state(checkpoint(5L, 0L), 760);
        PlannedCompaction planned = planned(state);
        ReadyCompaction ready = new ReadyCompaction(planned, false);
        HierarchicalCheckpoint published = checkpoint(6L, 1L);
        when(recovery.recover(EXECUTION, state.checkpoint())).thenReturn(RecoveryResult.none());
        when(assembler.toCompactionInput(state)).thenReturn(compactionInput(state));
        when(planning.plan(any(), any())).thenReturn(planned);
        when(generation.generate(planned)).thenReturn(ready);
        when(publication.publish(ready, state.request(), state.policy()))
                .thenReturn(PublishResult.PUBLISHED);
        when(checkpoints.findCheckpoint(SESSION_ID)).thenReturn(published);
        when(assembler.preparePublishedView(published, state.request(), POLICY))
                .thenReturn(
                        new PreparedConversationContext("hierarchical", List.of(), 750, true, 6L));

        CompactionOutcome outcome = coordinator.compactIfRequired(state);

        assertThat(outcome.targetReached()).isFalse();
        assertThat(outcome.withinHardLimit()).isTrue();
    }

    @Test
    void terminalRecoveryFailureNeverReplansCurrentAttempt() {
        HierarchicalContextState state = state(checkpoint(5L, 0L), 760);
        when(recovery.recover(EXECUTION, state.checkpoint()))
                .thenReturn(RecoveryResult.failed(false));

        assertThatThrownBy(() -> coordinator.compactIfRequired(state))
                .isInstanceOf(HierarchicalCompactionException.class)
                .hasMessage(HierarchicalCompactionException.FAILURE_CODE);
        verifyNoInteractions(planning, generation, publication);
        verify(executions, never()).fail(any(), any(), any());
    }

    @Test
    void mismatchedSucceededRecoveryReportsStableFailureWithoutReplanning() {
        HierarchicalContextState state = state(checkpoint(5L, 0L), 760);
        when(recovery.recover(EXECUTION, state.checkpoint()))
                .thenReturn(RecoveryResult.failed(true));

        assertThatThrownBy(() -> coordinator.compactIfRequired(state))
                .isInstanceOf(HierarchicalCompactionException.class)
                .hasMessage(HierarchicalCompactionException.FAILURE_CODE);
        verifyNoInteractions(planning, generation, publication);
        verify(executions)
                .fail(
                        EXECUTION,
                        HierarchicalCompactionException.FAILURE_CODE,
                        HierarchicalCompactionException.SAFE_MESSAGE);
    }

    @Test
    void publishedViewHardFailureIsReportedToExecutionControl() {
        HierarchicalContextState state = state(checkpoint(5L, 0L), 760);
        PlannedCompaction planned = planned(state);
        ReadyCompaction ready = new ReadyCompaction(planned, false);
        HierarchicalCheckpoint published = checkpoint(6L, 1L);

        // publication 已提交后，重建视图自身的 hard-limit 异常也必须落到稳定执行失败边界。
        when(recovery.recover(EXECUTION, state.checkpoint())).thenReturn(RecoveryResult.none());
        when(assembler.toCompactionInput(state)).thenReturn(compactionInput(state));
        when(planning.plan(any(), any())).thenReturn(planned);
        when(generation.generate(planned)).thenReturn(ready);
        when(publication.publish(ready, state.request(), state.policy()))
                .thenReturn(PublishResult.PUBLISHED);
        when(checkpoints.findCheckpoint(SESSION_ID)).thenReturn(published);
        when(assembler.preparePublishedView(published, state.request(), POLICY))
                .thenThrow(new HierarchicalContextWindowExceededException());

        assertThatThrownBy(() -> coordinator.compactIfRequired(state))
                .isInstanceOf(HierarchicalContextWindowExceededException.class)
                .hasMessage(HierarchicalContextWindowExceededException.FAILURE_CODE);
        verify(executions)
                .fail(
                        EXECUTION,
                        HierarchicalContextWindowExceededException.FAILURE_CODE,
                        HierarchicalContextWindowExceededException.FAILURE_CODE);
    }

    @Test
    void fixedAndCurrentHardFailureCreatesNoCandidates() {
        HierarchicalContextState state = state(checkpoint(5L, 0L), 900);
        when(recovery.recover(EXECUTION, state.checkpoint())).thenReturn(RecoveryResult.none());
        when(assembler.toCompactionInput(state))
                .thenThrow(new HierarchicalContextWindowExceededException());

        assertThatThrownBy(() -> coordinator.compactIfRequired(state))
                .isInstanceOf(HierarchicalContextWindowExceededException.class)
                .hasMessage(HierarchicalContextWindowExceededException.FAILURE_CODE);

        verify(executions)
                .fail(
                        EXECUTION,
                        HierarchicalContextWindowExceededException.FAILURE_CODE,
                        HierarchicalContextWindowExceededException.FAILURE_CODE);
        verifyNoInteractions(planning, generation, publication);
    }

    private static HierarchicalContextState state(
            HierarchicalCheckpoint checkpoint, int estimatedTokens) {
        ConversationContextRequest request =
                new ConversationContextRequest(EXECUTION, BUDGET, List.of(), List.of());
        return new HierarchicalContextState(
                request, POLICY, List.of(), null, checkpoint, estimatedTokens);
    }

    private static HierarchicalConversationContextAssembler.CompactionInput compactionInput(
            HierarchicalContextState state) {
        PlanningInput planningInput =
                new PlanningInput(
                        List.of(),
                        state.checkpoint(),
                        List.of(),
                        BUDGET,
                        0,
                        state.estimatedTokens());
        return new HierarchicalConversationContextAssembler.CompactionInput(
                state, planningInput, emptyPlan(), false);
    }

    private static PlannedCompaction planned(HierarchicalContextState state) {
        HierarchicalCompactionJob job =
                new HierarchicalCompactionJob(
                        JOB_ID,
                        SESSION_ID,
                        EXECUTION.executionId(),
                        EXECUTION.executionAttempt(),
                        EXECUTION.ownerInstanceId(),
                        EXECUTION.inputUpToMessageSequence(),
                        state.checkpoint().version(),
                        state.checkpoint().strategyGeneration(),
                        "{\"schemaVersion\":3,\"warmTokenBudget\":600}",
                        HierarchicalCompactionJob.Status.PLANNED,
                        null,
                        0L);
        GenerationSettings settings =
                new GenerationSettings(POLICY, MAX_SUMMARY_TOKENS, 1, 1, "provider", "model");
        return new PlannedCompaction(job, EXECUTION, settings, 600, emptyPlan(), List.of(), null);
    }

    private static HierarchicalCompactionPlan emptyPlan() {
        return new HierarchicalCompactionPlan(
                0,
                Optional.empty(),
                TurnRange.EMPTY,
                TurnRange.EMPTY,
                TurnRange.EMPTY,
                List.of(),
                List.of(),
                FrontierSelection.empty(),
                List.of());
    }

    private static HierarchicalCheckpoint checkpoint(long generation, long version) {
        return new HierarchicalCheckpoint(
                "checkpoint-coordinator",
                SESSION_ID,
                "{\"schemaVersion\":1}",
                0,
                0,
                0,
                1,
                1,
                "provider",
                "model",
                "{\"schemaVersion\":1,\"nodes\":[]}",
                generation,
                version);
    }
}
