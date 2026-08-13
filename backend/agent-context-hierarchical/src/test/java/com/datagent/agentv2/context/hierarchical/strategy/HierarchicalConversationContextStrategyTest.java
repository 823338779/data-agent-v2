package com.datagent.agentv2.context.hierarchical.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.hierarchical.configuration.HierarchicalContextProperties;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextState;
import com.datagent.agentv2.context.hierarchical.observability.HierarchicalContextMetrics;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCheckpointRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalExecutionPolicyRepository;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionCoordinator;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionCoordinator.CompactionOutcome;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalContextWindowExceededException;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalConversationContextAssembler;
import com.datagent.agentv2.conversation.context.ContextBudget;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextSummaryModelIdentity;
import com.datagent.agentv2.conversation.context.ContextSummaryModelPort;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import com.datagent.agentv2.conversation.context.PreparedConversationContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class HierarchicalConversationContextStrategyTest {

    private static final HierarchicalContextPolicySnapshot CONFIGURED_POLICY =
            new HierarchicalContextPolicySnapshot(1, 0.75d, 0.70d, 10, 100, 1, 1);
    private static final HierarchicalContextPolicySnapshot FROZEN_POLICY =
            new HierarchicalContextPolicySnapshot(1, 0.80d, 0.72d, 8, 80, 1, 1);

    private HierarchicalExecutionPolicyRepository policies;
    private HierarchicalCheckpointRepository checkpoints;
    private HierarchicalConversationContextAssembler assembler;
    private HierarchicalCompactionCoordinator coordinator;
    private HierarchicalContextMetrics metrics;
    private ContextSummaryModelPort summaryModel;
    private IdGenerator ids;
    private ConversationContextRequest request;
    private HierarchicalConversationContextStrategy strategy;

    @BeforeEach
    void setUp() {
        policies = mock(HierarchicalExecutionPolicyRepository.class);
        checkpoints = mock(HierarchicalCheckpointRepository.class);
        assembler = mock(HierarchicalConversationContextAssembler.class);
        coordinator = mock(HierarchicalCompactionCoordinator.class);
        metrics = mock(HierarchicalContextMetrics.class);
        summaryModel = mock(ContextSummaryModelPort.class);
        ids = mock(IdGenerator.class);
        request = request();
        HierarchicalContextProperties properties = properties();
        strategy =
                new HierarchicalConversationContextStrategy(
                        policies,
                        checkpoints,
                        assembler,
                        coordinator,
                        metrics,
                        summaryModel,
                        ids,
                        properties);
        when(policies.findOrCreate(request.execution(), CONFIGURED_POLICY))
                .thenReturn(FROZEN_POLICY);
        when(summaryModel.identity())
                .thenReturn(new ContextSummaryModelIdentity("provider", "model"));
        when(ids.next("hierarchical_checkpoint")).thenReturn("checkpoint");
    }

    @Test
    void freezesPolicyBeforeAnyHistoryReadAndSkipsCompactionAtTrigger() {
        HierarchicalContextState state = state(80, checkpoint(0L, 0L));
        PreparedConversationContext expected = prepared(80, false, 0L);
        when(assembler.loadAndEstimate(request, FROZEN_POLICY)).thenReturn(state);
        when(assembler.prepareCurrentView(state)).thenReturn(expected);
        when(assembler.activeFrontierCounts(state.checkpoint(), FROZEN_POLICY))
                .thenReturn(Map.of());

        PreparedConversationContext prepared = strategy.prepare(request);

        // Policy persistence is the first observable side effect and fixes every later decision.
        InOrder order = inOrder(policies, checkpoints, assembler);
        order.verify(policies).findOrCreate(request.execution(), CONFIGURED_POLICY);
        order.verify(checkpoints)
                .create(
                        new HierarchicalCheckpoint(
                                "checkpoint",
                                "session",
                                "{\"schemaVersion\":1}",
                                0L,
                                0L,
                                0,
                                1,
                                1,
                                "provider",
                                "model",
                                "{\"schemaVersion\":1,\"nodes\":[]}",
                                0L,
                                0L));
        order.verify(assembler).loadAndEstimate(request, FROZEN_POLICY);
        verify(coordinator, never()).compactIfRequired(state);
        assertThat(prepared).isSameAs(expected);
        assertThat(prepared.compacted()).isFalse();
    }

    @Test
    void compactsAboveFrozenTriggerAndReturnsPublishedTargetView() {
        HierarchicalContextState state = state(81, checkpoint(0L, 0L));
        HierarchicalCheckpoint published = checkpoint(30L, 1L);
        PreparedConversationContext expected = prepared(70, true, 1L);
        when(assembler.loadAndEstimate(request, FROZEN_POLICY)).thenReturn(state);
        when(coordinator.compactIfRequired(state))
                .thenReturn(new CompactionOutcome(published, true, true));
        when(assembler.preparePublishedView(published, request, FROZEN_POLICY))
                .thenReturn(expected);
        when(assembler.activeFrontierCounts(published, FROZEN_POLICY)).thenReturn(Map.of(1, 2));

        PreparedConversationContext prepared = strategy.prepare(request);

        assertThat(prepared).isSameAs(expected);
        verify(coordinator).compactIfRequired(state);
        verify(assembler).preparePublishedView(published, request, FROZEN_POLICY);
    }

    @Test
    void acceptsPublishedProtectedMinimumAboveTargetWhenStillWithinHardLimit() {
        HierarchicalContextState state = state(90, checkpoint(0L, 0L));
        HierarchicalCheckpoint published = checkpoint(40L, 2L);
        PreparedConversationContext protectedMinimum = prepared(73, true, 2L);
        when(assembler.loadAndEstimate(request, FROZEN_POLICY)).thenReturn(state);
        when(coordinator.compactIfRequired(state))
                .thenReturn(new CompactionOutcome(published, false, true));
        when(assembler.preparePublishedView(published, request, FROZEN_POLICY))
                .thenReturn(protectedMinimum);
        when(assembler.activeFrontierCounts(published, FROZEN_POLICY)).thenReturn(Map.of(0, 3));

        PreparedConversationContext prepared = strategy.prepare(request);

        assertThat(prepared.estimatedInputTokens()).isEqualTo(73);
        assertThat(prepared.compacted()).isTrue();
    }

    @Test
    void delegatesHardLimitHotDegradationToCoordinatorBeforePublishing() {
        HierarchicalContextState state = state(96, checkpoint(0L, 0L));
        HierarchicalCheckpoint degraded = checkpoint(50L, 3L);
        PreparedConversationContext expected = prepared(84, true, 3L);
        when(assembler.loadAndEstimate(request, FROZEN_POLICY)).thenReturn(state);
        when(coordinator.compactIfRequired(state))
                .thenReturn(new CompactionOutcome(degraded, false, true));
        when(assembler.preparePublishedView(degraded, request, FROZEN_POLICY)).thenReturn(expected);
        when(assembler.activeFrontierCounts(degraded, FROZEN_POLICY)).thenReturn(Map.of(0, 1));

        PreparedConversationContext prepared = strategy.prepare(request);

        assertThat(prepared.estimatedInputTokens())
                .isLessThanOrEqualTo(request.budget().hardInputLimitTokens());
        InOrder order = inOrder(coordinator, assembler);
        order.verify(coordinator).compactIfRequired(state);
        order.verify(assembler).preparePublishedView(degraded, request, FROZEN_POLICY);
    }

    @Test
    void propagatesCurrentTurnHardFailureWithoutStartingCompaction() {
        when(assembler.loadAndEstimate(request, FROZEN_POLICY))
                .thenThrow(new HierarchicalContextWindowExceededException());

        assertThatThrownBy(() -> strategy.prepare(request))
                .isInstanceOf(HierarchicalContextWindowExceededException.class);

        verify(coordinator, never()).compactIfRequired(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void migratesAChangedSummaryModelIdentityBeforeReadingHistory() {
        HierarchicalContextState state = state(80, checkpoint(0L, 0L));
        when(summaryModel.identity())
                .thenReturn(new ContextSummaryModelIdentity("provider-v2", "model-v2"));
        when(assembler.loadAndEstimate(request, FROZEN_POLICY)).thenReturn(state);
        when(assembler.prepareCurrentView(state)).thenReturn(prepared(80, false, 0L));
        when(assembler.activeFrontierCounts(state.checkpoint(), FROZEN_POLICY))
                .thenReturn(Map.of());

        strategy.prepare(request);

        InOrder order = inOrder(checkpoints, assembler);
        order.verify(checkpoints)
                .migrateIdentityIfRequired(
                        request.execution(),
                        FROZEN_POLICY,
                        new ContextSummaryModelIdentity("provider-v2", "model-v2"));
        order.verify(assembler).loadAndEstimate(request, FROZEN_POLICY);
    }

    private HierarchicalContextState state(int estimatedTokens, HierarchicalCheckpoint checkpoint) {
        return new HierarchicalContextState(
                request, FROZEN_POLICY, List.of(), null, checkpoint, estimatedTokens);
    }

    private static ConversationContextRequest request() {
        return new ConversationContextRequest(
                new ContextExecutionRef("tenant", "session", "execution", "owner", 1L, 9L),
                new ContextBudget(100, 10, 5, 0),
                List.of(),
                List.of());
    }

    private static HierarchicalContextProperties properties() {
        HierarchicalContextProperties properties = new HierarchicalContextProperties();
        properties.setTriggerRatio(CONFIGURED_POLICY.triggerRatio());
        properties.setTargetRatio(CONFIGURED_POLICY.targetRatio());
        properties.setRecentRawTurns(CONFIGURED_POLICY.recentRawTurns());
        properties.setWarmWindowTurns(CONFIGURED_POLICY.warmWindowTurns());
        return properties;
    }

    private static HierarchicalCheckpoint checkpoint(long coldPointer, long generation) {
        return new HierarchicalCheckpoint(
                "checkpoint",
                "session",
                "{}",
                coldPointer,
                coldPointer,
                10,
                1,
                1,
                "provider",
                "model",
                "{\"schemaVersion\":1,\"nodes\":[]}",
                generation,
                generation);
    }

    private static PreparedConversationContext prepared(
            int estimatedTokens, boolean compacted, long generation) {
        return new PreparedConversationContext(
                "hierarchical",
                List.of(ContextMessage.user("current")),
                estimatedTokens,
                compacted,
                generation);
    }
}
