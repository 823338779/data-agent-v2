package com.datagent.agentv2.context.hierarchical.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextState;
import com.datagent.agentv2.context.hierarchical.domain.RollingColdCompactionPlan;
import com.datagent.agentv2.context.hierarchical.domain.RollingColdCompactionPlan.TurnRange;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCheckpointRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalColdRepository;
import com.datagent.agentv2.context.hierarchical.planning.RollingColdCompactionPlanner;
import com.datagent.agentv2.context.hierarchical.summary.RollingColdSummaryGenerator;
import com.datagent.agentv2.conversation.context.ContextBudget;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextSummaryModelIdentity;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class HierarchicalCompactionCoordinatorTest {
    @Test
    void skipsGenerationWhenPlannerHasNoEligibleCoverage() {
        var planner = mock(RollingColdCompactionPlanner.class);
        var generator = mock(RollingColdSummaryGenerator.class);
        var colds = mock(HierarchicalColdRepository.class);
        var checkpoints = mock(HierarchicalCheckpointRepository.class);
        var assembler = mock(HierarchicalConversationContextAssembler.class);
        var control = mock(ContextExecutionControlPort.class);
        var execution = new ContextExecutionRef("tenant", "session", "execution", 20L);
        var request =
                new ConversationContextRequest(
                        execution, new ContextBudget(10_000, 100, 100, 0), List.of(), List.of());
        var checkpoint = new HierarchicalCheckpoint("checkpoint", "session", null, 0, 0, 0);
        var state =
                new HierarchicalContextState(
                        request,
                        new HierarchicalContextPolicySnapshot(2, .75, .70, 10, 2, 2),
                        List.of(),
                        null,
                        checkpoint,
                        8_000);
        var plan =
                new RollingColdCompactionPlan(
                        20,
                        null,
                        TurnRange.EMPTY,
                        TurnRange.EMPTY,
                        TurnRange.EMPTY,
                        null,
                        2_000,
                        List.of());
        when(assembler.compactionInput(state, 32_768))
                .thenReturn(
                        new HierarchicalConversationContextAssembler.CompactionInput(
                                state, null, 2_000, 32_768));
        when(planner.plan(eq(20L), any(), any(), eq(2_000), eq(32_768))).thenReturn(plan);
        var coordinator =
                new HierarchicalCompactionCoordinator(
                        planner, generator, colds, checkpoints, assembler, control, 1_024, 32_768);

        var outcome =
                coordinator.compactIfRequired(
                        state, new ContextSummaryModelIdentity("provider", "model"));

        assertThat(outcome.checkpoint()).isEqualTo(checkpoint);
        assertThat(outcome.compacted()).isFalse();
        verifyNoInteractions(generator, colds, checkpoints);
    }
}
