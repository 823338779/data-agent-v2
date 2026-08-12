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
import java.util.List;
import org.junit.jupiter.api.Test;

class LsmConversationContextStrategyTest {

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
        LsmConversationContextAssembler.ContextState state =
                LsmConversationContextAssembler.ContextState.forTest(
                        request, List.of(ContextMessage.user("question")), 76);
        CompactionRequest compactionRequest = mock(CompactionRequest.class);
        LsmContextManifest published =
                new LsmContextManifest(null, List.of(), null, null, null, null, 4, 5, 4);
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

        PreparedConversationContext prepared =
                new LsmConversationContextStrategy(policies, assembler, coordinator)
                        .prepare(request);

        assertThat(prepared).isSameAs(expected);
        verify(coordinator).compactIfRequired(compactionRequest);
        verify(assembler).preparePublishedView(published, request);
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
    void failsWithStableCodeWhenCurrentTurnAloneExceedsHardLimit() {
        ContextExecutionControlPort executionControl = mock(ContextExecutionControlPort.class);
        LsmConversationContextAssembler assembler = assembler(executionControl);
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
                null);
    }
}
