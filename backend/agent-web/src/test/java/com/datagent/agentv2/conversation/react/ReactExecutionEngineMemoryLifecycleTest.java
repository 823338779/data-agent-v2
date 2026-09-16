package com.datagent.agentv2.conversation.react;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.conversation.context.ContextModelMessageMapper;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import com.datagent.agentv2.conversation.context.ConversationContextRequestFactory;
import com.datagent.agentv2.conversation.context.ConversationContextStrategyResolver;
import com.datagent.agentv2.conversation.context.PreparedConversationContext;
import com.datagent.agentv2.conversation.exception.ModelStreamInterruptedException;
import com.datagent.agentv2.conversation.model.api.AgentModelGateway;
import com.datagent.agentv2.conversation.model.api.ModelInvocationResult;
import com.datagent.agentv2.conversation.runtime.LocalAgentExecution;
import com.datagent.agentv2.conversation.runtime.LocalExecutionGuard;
import com.datagent.agentv2.conversation.tool.ToolExecutionCoordinator;
import com.datagent.agentv2.conversation.tool.runtime.ExecutionToolView;
import com.datagent.agentv2.conversation.tool.runtime.ExecutionToolViews;
import com.datagent.agentv2.memory.MemoryEvidence;
import com.datagent.agentv2.memory.MemoryKind;
import com.datagent.agentv2.memory.MemoryQuota;
import com.datagent.agentv2.memory.MemorySnapshot;
import com.datagent.agentv2.memory.MemoryState;
import com.datagent.agentv2.memory.tool.MemoryInvocationRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReactExecutionEngineMemoryLifecycleTest {

    private final AgentModelGateway model = mock(AgentModelGateway.class);
    private final ConversationContextStrategyResolver contexts =
            mock(ConversationContextStrategyResolver.class);
    private final ConversationContextRequestFactory contextRequests =
            mock(ConversationContextRequestFactory.class);
    private final ContextModelMessageMapper contextMessages =
            mock(ContextModelMessageMapper.class);
    private final ModelInvocationCoordinator invocations = mock(ModelInvocationCoordinator.class);
    private final ToolExecutionCoordinator tools = mock(ToolExecutionCoordinator.class);
    private final ExecutionToolViews toolViews = mock(ExecutionToolViews.class);
    private final ToolModelDefinitionMapper toolDefinitions = mock(ToolModelDefinitionMapper.class);
    private final LocalExecutionGuard guard = mock(LocalExecutionGuard.class);
    private final MemoryInvocationRegistry memoryInvocations = new MemoryInvocationRegistry();
    private final LocalAgentExecution run =
            new LocalAgentExecution("tenant_local", "conversation-1", "execution-1", 2);
    private final ConversationContextRequest request = mock(ConversationContextRequest.class);
    private final ReactExecutionEngine engine =
            new ReactExecutionEngine(
                    model,
                    contexts,
                    contextRequests,
                    contextMessages,
                    invocations,
                    tools,
                    toolViews,
                    toolDefinitions,
                    guard,
                    new ReactLimitsProperties(4, 4),
                    memoryInvocations);

    @BeforeEach
    void prepareCommonModelInput() {
        ExecutionToolView view = mock(ExecutionToolView.class);
        when(toolViews.require(run.executionId())).thenReturn(view);
        when(view.visibleTools()).thenReturn(List.of());
        when(toolDefinitions.toModelDefinitions(anyList())).thenReturn(List.of());
        when(contexts.prepare(request))
                .thenReturn(new PreparedConversationContext("raw", List.of(), 0, false, 0));
        when(contextMessages.toModelMessages(anyList())).thenReturn(List.of());
        when(contextRequests.create(any(), any(), anyList()))
                .thenAnswer(
                        invocation -> {
                            ModelInvocationCoordinator.ModelInvocationBoundary boundary =
                                    invocation.getArgument(1);
                            memoryInvocations.open(
                                    run.executionId(),
                                    boundary.modelInvocationSequence(),
                                    snapshot(),
                                    List.of(evidence()));
                            return request;
                        });
    }

    @Test
    void closesEveryInvocationAcrossToolCallsContinueAndFinalAnswer() {
        var first = new ModelInvocationCoordinator.ModelInvocationBoundary(1, 2);
        var second = new ModelInvocationCoordinator.ModelInvocationBoundary(2, 2);
        var call =
                new ModelToolCall(
                        "call-1",
                        "echo",
                        JsonMapper.builder().build().createObjectNode().put("value", 1));
        when(invocations.beginModelInvocation(run)).thenReturn(first, second);
        when(model.complete(anyList(), anyList(), any()))
                .thenReturn(
                        new ModelInvocationResult.ToolCalls(List.of(call)),
                        new ModelInvocationResult.FinalAnswer("完成"));
        when(invocations.archiveToolCalls(run, first, List.of(call))).thenReturn(List.of());

        engine.execute(run);

        assertClosed(1);
        assertClosed(2);
        verify(tools).executeModelInvocationTools(run, 1, List.of());
        verify(invocations).completeFinalAnswer(run, second, "完成");
    }

    @Test
    void closesInvocationWhenModelFails() {
        var boundary = new ModelInvocationCoordinator.ModelInvocationBoundary(1, 2);
        var failure = new ModelStreamInterruptedException("provider interrupted");
        when(invocations.beginModelInvocation(run)).thenReturn(boundary);
        when(model.complete(anyList(), anyList(), any())).thenThrow(failure);

        assertThatThrownBy(() -> engine.execute(run)).isSameAs(failure);

        assertClosed(1);
        verify(invocations).failModelOutput(run, boundary);
    }

    private void assertClosed(long sequence) {
        var context =
                new com.datagent.agentv2.conversation.tool.AgentTool.InvocationContext(
                        "tenant_local", "execution-1", sequence, "call", "request");
        assertThatThrownBy(() -> memoryInvocations.contextFor(context))
                .isInstanceOf(IllegalStateException.class);
    }

    private static MemorySnapshot snapshot() {
        return new MemorySnapshot(
                new MemoryState(3, 0, true, true, true, 1, LocalDateTime.now()),
                List.of(),
                Map.of(
                        MemoryKind.PERSON_PROFILE,
                        new MemoryQuota(0, 800),
                        MemoryKind.COLLABORATION_PREFERENCE,
                        new MemoryQuota(0, 1500)));
    }

    private static MemoryEvidence evidence() {
        return new MemoryEvidence(
                "message-1",
                MemoryEvidence.Kind.USER_MESSAGE,
                "conversation-1",
                "execution-1",
                1,
                "user_admin",
                1,
                "请记住",
                LocalDateTime.now());
    }
}
