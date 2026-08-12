package com.datagent.agentv2.context.lsm.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.lsm.domain.ConversationTurn;
import com.datagent.agentv2.context.lsm.domain.LsmContextManifest;
import com.datagent.agentv2.context.lsm.observability.LsmContextMetrics;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmSummarySegmentEntity;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmExecutionPolicyRepository;
import com.datagent.agentv2.context.lsm.planning.ConversationTokenEstimator;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.CompactionOutcome;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.CompactionRequest;
import com.datagent.agentv2.context.lsm.runtime.LsmConversationContextAssembler;
import com.datagent.agentv2.conversation.context.ContextBudget;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextPolicySnapshot;
import com.datagent.agentv2.conversation.context.ContextPreparationException;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import com.datagent.agentv2.conversation.context.PreparedConversationContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class LsmConversationContextStrategyTest {

    @Test
    void recordsInputPressureAndActiveSegmentLevelsWithoutConversationLabels() {
        LsmExecutionPolicyRepository policies = mock(LsmExecutionPolicyRepository.class);
        LsmConversationContextAssembler assembler = mock(LsmConversationContextAssembler.class);
        LsmCompactionCoordinator coordinator = mock(LsmCompactionCoordinator.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LsmContextMetrics metrics = new LsmContextMetrics(registry);
        ConversationContextRequest request = request(policy(5));
        LsmSummarySegmentEntity levelZero = new LsmSummarySegmentEntity();
        levelZero.setSummarySegmentId("active-zero");
        levelZero.setLevel(0);
        LsmSummarySegmentEntity levelTwo = new LsmSummarySegmentEntity();
        levelTwo.setSummarySegmentId("active-two");
        levelTwo.setLevel(2);
        LsmSummarySegmentEntity inactiveLevelFour = new LsmSummarySegmentEntity();
        inactiveLevelFour.setSummarySegmentId("inactive-four");
        inactiveLevelFour.setLevel(4);
        LsmContextManifest manifest =
                new LsmContextManifest(
                        null,
                        List.of("active-zero", "active-two"),
                        null,
                        null,
                        null,
                        null,
                        1,
                        5,
                        4);
        var state =
                new LsmConversationContextAssembler.ContextState(
                        request,
                        List.of(),
                        List.of(ContextMessage.user("question")),
                        manifest,
                        List.of(levelZero, levelTwo, inactiveLevelFour),
                        50);
        when(policies.findOrCreateExecutionPolicy(request.execution(), request.policy()))
                .thenReturn(request.policy());
        when(assembler.loadAndEstimate(request)).thenReturn(state);
        when(assembler.activeSegmentCounts(manifest)).thenReturn(java.util.Map.of(0, 1, 2, 1));
        when(assembler.prepareCurrentView(state))
                .thenReturn(
                        new PreparedConversationContext(
                                "lsm", List.of(ContextMessage.user("question")), 50, false, 0));

        new LsmConversationContextStrategy(policies, assembler, coordinator, metrics)
                .prepare(request);

        assertThat(registry.get("agent_context_input_estimated_tokens").gauge().value())
                .isEqualTo(50d);
        assertThat(registry.get("agent_context_utilization_ratio").gauge().value()).isEqualTo(0.5d);
        assertThat(registry.get("agent_context_active_segments").tag("level", "0").gauge().value())
                .isEqualTo(1d);
        assertThat(registry.get("agent_context_active_segments").tag("level", "2").gauge().value())
                .isEqualTo(1d);
        assertThat(registry.find("agent_context_active_segments").tag("level", "4").gauge())
                .isNull();
    }

    @Test
    void freezesPolicyBeforeReturningNoopBelowSoftTrigger() {
        LsmExecutionPolicyRepository policies = mock(LsmExecutionPolicyRepository.class);
        LsmConversationContextAssembler assembler = mock(LsmConversationContextAssembler.class);
        LsmCompactionCoordinator coordinator = mock(LsmCompactionCoordinator.class);
        ContextPolicySnapshot configured = policy(5);
        ContextPolicySnapshot frozen = policy(3);
        ConversationContextRequest request = request(configured);
        when(policies.findOrCreateExecutionPolicy(request.execution(), configured))
                .thenReturn(frozen);
        when(assembler.loadAndEstimate(request.withPolicy(frozen)))
                .thenReturn(
                        LsmConversationContextAssembler.ContextState.forTest(
                                request.withPolicy(frozen),
                                List.of(ContextMessage.user("question")),
                                request.budget().softTriggerTokens()));
        when(assembler.prepareCurrentView(org.mockito.ArgumentMatchers.any()))
                .thenReturn(
                        new PreparedConversationContext(
                                "lsm", List.of(ContextMessage.user("question")), 75, false, 0));

        var prepared =
                new LsmConversationContextStrategy(policies, assembler, coordinator)
                        .prepare(request);

        assertThat(prepared.compacted()).isFalse();
        verify(policies).findOrCreateExecutionPolicy(request.execution(), configured);
        verify(coordinator, never()).compactIfRequired(org.mockito.ArgumentMatchers.any());
        var order = inOrder(policies, assembler);
        order.verify(policies).findOrCreateExecutionPolicy(request.execution(), configured);
        order.verify(assembler).loadAndEstimate(request.withPolicy(frozen));
    }

    @Test
    void compactsAboveSoftTriggerBeforePreparingPublishedView() {
        LsmExecutionPolicyRepository policies = mock(LsmExecutionPolicyRepository.class);
        LsmConversationContextAssembler assembler = mock(LsmConversationContextAssembler.class);
        LsmCompactionCoordinator coordinator = mock(LsmCompactionCoordinator.class);
        ConversationContextRequest request = request(policy(5));
        LsmSummarySegmentEntity oldLevelZero = new LsmSummarySegmentEntity();
        oldLevelZero.setSummarySegmentId("old-zero");
        oldLevelZero.setLevel(0);
        LsmContextManifest previous =
                new LsmContextManifest(null, List.of("old-zero"), null, null, null, null, 3, 5, 4);
        LsmConversationContextAssembler.ContextState state =
                new LsmConversationContextAssembler.ContextState(
                        request,
                        List.of(),
                        List.of(ContextMessage.user("question")),
                        previous,
                        List.of(oldLevelZero),
                        76);
        CompactionRequest compactionRequest = mock(CompactionRequest.class);
        LsmContextManifest published =
                new LsmContextManifest(null, List.of("new-two"), null, null, null, null, 4, 5, 4);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LsmContextMetrics metrics = new LsmContextMetrics(registry);
        PreparedConversationContext expected =
                new PreparedConversationContext(
                        "lsm", List.of(ContextMessage.user("question")), 60, true, 4);
        when(policies.findOrCreateExecutionPolicy(request.execution(), request.policy()))
                .thenReturn(request.policy());
        when(assembler.loadAndEstimate(request)).thenReturn(state);
        when(assembler.toCompactionRequest(state)).thenReturn(compactionRequest);
        when(coordinator.compactIfRequired(compactionRequest))
                .thenReturn(CompactionOutcome.unchanged(published));
        when(assembler.preparePublishedView(published, request)).thenReturn(expected);
        when(assembler.activeSegmentCounts(published)).thenReturn(java.util.Map.of(2, 1));

        PreparedConversationContext prepared =
                new LsmConversationContextStrategy(policies, assembler, coordinator, metrics)
                        .prepare(request);

        assertThat(prepared).isSameAs(expected);
        verify(coordinator).compactIfRequired(compactionRequest);
        verify(assembler).preparePublishedView(published, request);
        var observationOrder = inOrder(assembler);
        observationOrder.verify(assembler).preparePublishedView(published, request);
        observationOrder.verify(assembler).activeSegmentCounts(published);
        assertThat(registry.find("agent_context_active_segments").tag("level", "0").gauge())
                .isNull();
        assertThat(registry.get("agent_context_active_segments").tag("level", "2").gauge().value())
                .isEqualTo(1d);
    }

    @Test
    void degradesOldestCompleteHotTurnOnlyWhenHardLimitIsExceeded() {
        ContextExecutionControlPort executionControl = mock(ContextExecutionControlPort.class);
        LsmConversationContextAssembler assembler = assembler(executionControl);
        ConversationContextRequest request = hardLimitRequest();
        ContextMessage oldUser = ContextMessage.user("u".repeat(45));
        ContextMessage oldAssistant = ContextMessage.assistant("a".repeat(45), null);
        ContextMessage current = ContextMessage.user("current");
        List<LsmConversationContextAssembler.TurnSource> turns =
                List.of(
                        new LsmConversationContextAssembler.TurnSource(
                                new ConversationTurn(1, List.of(oldUser, oldAssistant), true),
                                1,
                                2),
                        new LsmConversationContextAssembler.TurnSource(
                                new ConversationTurn(2, List.of(current), false), 3, 3));
        var state =
                new LsmConversationContextAssembler.ContextState(
                        request,
                        turns,
                        List.of(oldUser, oldAssistant, current),
                        LsmContextManifest.empty(),
                        List.of(),
                        130);

        PreparedConversationContext prepared = assembler.prepareCurrentView(state);

        assertThat(prepared.messages()).containsExactly(current);
        assertThat(prepared.estimatedInputTokens())
                .isLessThanOrEqualTo(request.budget().hardInputLimitTokens());
        verify(executionControl, never()).fail(any(), any(), any());
    }

    @Test
    void hardLimitDegradationKeepsCurrentMessageWhenOldTurnHasEqualContent() {
        ContextExecutionControlPort executionControl = mock(ContextExecutionControlPort.class);
        LsmConversationContextAssembler assembler = assembler(executionControl);
        ConversationContextRequest request = hardLimitRequest();
        ContextMessage oldUser = ContextMessage.user("same-content");
        ContextMessage oldAssistant = ContextMessage.assistant("a".repeat(100), null);
        ContextMessage current = ContextMessage.user("same-content");
        List<LsmConversationContextAssembler.TurnSource> turns =
                List.of(
                        new LsmConversationContextAssembler.TurnSource(
                                new ConversationTurn(1, List.of(oldUser, oldAssistant), true),
                                1,
                                2),
                        new LsmConversationContextAssembler.TurnSource(
                                new ConversationTurn(2, List.of(current), false), 3, 3));
        var state =
                new LsmConversationContextAssembler.ContextState(
                        request,
                        turns,
                        List.of(oldUser, oldAssistant, current),
                        LsmContextManifest.empty(),
                        List.of(),
                        150);

        PreparedConversationContext prepared = assembler.prepareCurrentView(state);

        assertThat(prepared.messages()).containsExactly(current);
        verify(executionControl, never()).fail(any(), any(), any());
    }

    @Test
    void failsWithStableCodeWhenCurrentTurnAloneExceedsHardLimit() {
        ContextExecutionControlPort executionControl = mock(ContextExecutionControlPort.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LsmConversationContextAssembler assembler =
                assembler(executionControl, new LsmContextMetrics(registry));
        ConversationContextRequest request = hardLimitRequest();
        ContextMessage current = ContextMessage.user("x".repeat(140));
        List<LsmConversationContextAssembler.TurnSource> turns =
                List.of(
                        new LsmConversationContextAssembler.TurnSource(
                                new ConversationTurn(1, List.of(current), false), 1, 1));
        var state =
                new LsmConversationContextAssembler.ContextState(
                        request,
                        turns,
                        List.of(current),
                        LsmContextManifest.empty(),
                        List.of(),
                        151);

        assertThatThrownBy(() -> assembler.prepareCurrentView(state))
                .isInstanceOf(ContextPreparationException.class)
                .hasMessage("Context window was exceeded");
        verify(executionControl)
                .fail(
                        eq(request.execution()),
                        eq("CONTEXT_WINDOW_EXCEEDED"),
                        eq("Context window was exceeded"));
        assertThat(registry.get("agent_context_window_exceeded_total").counter().count())
                .isEqualTo(1d);
    }

    private static ConversationContextRequest request(ContextPolicySnapshot policy) {
        return new ConversationContextRequest(
                new ContextExecutionRef("tenant", "session", "execution", "owner", 1, 9),
                new ContextBudget(100, 10, 5, 0),
                policy,
                List.of(),
                List.of());
    }

    private static ContextPolicySnapshot policy(int fanout) {
        return new ContextPolicySnapshot(1, fanout, 4, 10, 75, 65, 100, 109, 10);
    }

    private static ConversationContextRequest hardLimitRequest() {
        return new ConversationContextRequest(
                new ContextExecutionRef("tenant", "session", "execution", "owner", 1, 9),
                new ContextBudget(140, 10, 10, 0),
                policy(5),
                List.of(),
                List.of());
    }

    private static LsmConversationContextAssembler assembler(
            ContextExecutionControlPort executionControl) {
        return assembler(executionControl, LsmContextMetrics.isolated());
    }

    private static LsmConversationContextAssembler assembler(
            ContextExecutionControlPort executionControl, LsmContextMetrics metrics) {
        return new LsmConversationContextAssembler(
                null,
                null,
                null,
                null,
                null,
                null,
                new ConversationTokenEstimator(),
                executionControl,
                null,
                null,
                metrics);
    }
}
