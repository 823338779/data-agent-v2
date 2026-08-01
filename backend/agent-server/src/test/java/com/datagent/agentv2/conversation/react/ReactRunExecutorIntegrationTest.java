package com.datagent.agentv2.conversation.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.conversation.model.AgentModelGateway;
import com.datagent.agentv2.conversation.model.ModelTurnResult;
import com.datagent.agentv2.conversation.runtime.RunExecutionPort;
import com.datagent.agentv2.conversation.runtime.RunLeaseService;
import com.datagent.agentv2.conversation.tool.AgentToolRegistry;
import com.datagent.agentv2.conversation.tool.ToolExecutionCoordinator;
import com.datagent.agentv2.conversation.tool.ToolObservation;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReactRunExecutorIntegrationTest {

    @Test
    void runsThreeModelTurnsReadsAllObservationsAndCompletesFinalAnswer() {
        AgentModelGateway model = mock(AgentModelGateway.class);
        ConversationContextLoader contexts = mock(ConversationContextLoader.class);
        ModelTurnCoordinator turns = mock(ModelTurnCoordinator.class);
        ToolExecutionCoordinator tools = mock(ToolExecutionCoordinator.class);
        RunLeaseService leases = mock(RunLeaseService.class);
        AgentToolRegistry registry = new AgentToolRegistry(List.of());
        ReactLimitsProperties limits = new ReactLimitsProperties(12, 32);
        RunExecutionPort.OwnedRun run =
                new RunExecutionPort.OwnedRun("tenant-react", "run-react", "owner-react", 1);
        AtomicInteger invocation = new AtomicInteger();
        ToolCallDocument first = call("call-1", "first");
        ToolCallDocument second = call("call-2", "second");
        ToolCallDocument failed = call("call-3", "failed");

        when(turns.beginTurn(run))
                .thenReturn(
                        new ModelTurnCoordinator.TurnBoundary(1, 1),
                        new ModelTurnCoordinator.TurnBoundary(2, 3),
                        new ModelTurnCoordinator.TurnBoundary(3, 6));
        when(contexts.load("run-react", 1))
                .thenReturn(List.of(ModelInputMessage.plain("user", "question")));
        when(contexts.load("run-react", 3))
                .thenReturn(
                        List.of(
                                ModelInputMessage.plain("user", "question"),
                                ModelInputMessage.assistantToolCalls(List.of(first)),
                                ModelInputMessage.tool("success-one", "call-1", "first")));
        when(contexts.load("run-react", 6))
                .thenReturn(
                        List.of(
                                ModelInputMessage.plain("user", "question"),
                                ModelInputMessage.assistantToolCalls(List.of(first)),
                                ModelInputMessage.tool("success-one", "call-1", "first"),
                                ModelInputMessage.assistantToolCalls(List.of(second, failed)),
                                ModelInputMessage.tool("success-two", "call-2", "second"),
                                ModelInputMessage.tool("failed-three", "call-3", "failed")));
        when(model.complete(any(), any(), any()))
                .thenAnswer(
                        ignored ->
                                switch (invocation.incrementAndGet()) {
                                    case 1 -> new ModelTurnResult.ToolCalls(List.of(first));
                                    case 2 ->
                                            new ModelTurnResult.ToolCalls(List.of(second, failed));
                                    default -> new ModelTurnResult.FinalAnswer("final answer");
                                });
        when(tools.executeTurn(run, 1))
                .thenReturn(
                        List.of(
                                ToolObservation.succeeded(
                                        JsonMapper.builder().build().readTree("{}"))));
        when(tools.executeTurn(run, 2))
                .thenReturn(
                        List.of(
                                ToolObservation.succeeded(
                                        JsonMapper.builder().build().readTree("{}")),
                                ToolObservation.failed("business", "failed")));

        new ReactRunExecutor(model, contexts, turns, tools, registry, leases, limits).execute(run);

        assertThat(invocation).hasValue(3);
        verify(turns)
                .archiveToolCalls(run, new ModelTurnCoordinator.TurnBoundary(1, 1), List.of(first));
        verify(turns)
                .archiveToolCalls(
                        run, new ModelTurnCoordinator.TurnBoundary(2, 3), List.of(second, failed));
        verify(turns)
                .completeFinalAnswer(
                        run, new ModelTurnCoordinator.TurnBoundary(3, 6), "final answer");
        verify(leases, times(9)).assertOwnedAndWithinBudget(run);
        verify(leases, times(3)).cancelIfRequested(run);
    }

    private static ToolCallDocument call(String id, String name) {
        return new ToolCallDocument(
                id, name, JsonMapper.builder().build().readTree("{\"value\":1}"));
    }
}
