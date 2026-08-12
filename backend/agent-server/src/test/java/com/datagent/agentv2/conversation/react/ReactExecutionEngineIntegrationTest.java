package com.datagent.agentv2.conversation.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.conversation.context.ContextBudget;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextModelMessageMapper;
import com.datagent.agentv2.conversation.context.ContextPolicySnapshot;
import com.datagent.agentv2.conversation.context.ContextToolDefinition;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import com.datagent.agentv2.conversation.context.ConversationContextRequestFactory;
import com.datagent.agentv2.conversation.context.ConversationContextStrategyResolver;
import com.datagent.agentv2.conversation.context.PreparedConversationContext;
import com.datagent.agentv2.conversation.model.AgentModelGateway;
import com.datagent.agentv2.conversation.model.ModelInvocationResult;
import com.datagent.agentv2.conversation.runtime.AgentExecutionLeaseService;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.conversation.tool.AgentToolRegistry;
import com.datagent.agentv2.conversation.tool.ToolExecutionCoordinator;
import com.datagent.agentv2.conversation.tool.ToolExecutionResult;
import com.datagent.agentv2.conversation.tool.builtin.CurrentTimeTool;
import com.datagent.agentv2.conversation.tool.builtin.SystemTimezoneTool;
import com.datagent.agentv2.conversation.tool.builtin.WebSearchTool;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class ReactExecutionEngineIntegrationTest {

    @Test
    void runsRegisteredBuiltInToolsAcrossThreeModelInvocationsAndCompletesFinalAnswer() {
        JsonMapper json = JsonMapper.builder().build();
        AgentModelGateway model = mock(AgentModelGateway.class);
        ConversationContextStrategyResolver contexts =
                mock(ConversationContextStrategyResolver.class);
        ConversationContextRequestFactory contextRequests =
                mock(ConversationContextRequestFactory.class);
        ContextModelMessageMapper contextMessages = new ContextModelMessageMapper(json);
        ModelInvocationCoordinator modelInvocations = mock(ModelInvocationCoordinator.class);
        ToolExecutionCoordinator tools = mock(ToolExecutionCoordinator.class);
        AgentExecutionLeaseService leases = mock(AgentExecutionLeaseService.class);
        AgentToolRegistry registry =
                new AgentToolRegistry(
                        List.of(
                                new SystemTimezoneTool(json),
                                new CurrentTimeTool(json),
                                new WebSearchTool(RestClient.builder(), json)));
        ReactLimitsProperties limits = new ReactLimitsProperties(12, 32);
        AgentExecutionPort.ClaimedAgentExecution run =
                new AgentExecutionPort.ClaimedAgentExecution(
                        "tenant-react", "execution-react", "owner-react", 1);
        AtomicInteger invocation = new AtomicInteger();
        ModelToolCall first = call("call-1", "system_timezone", "{}");
        ModelToolCall second = call("call-2", "current_time", "{}");
        ModelToolCall failed = call("call-3", "web_search", "{\"query\":\"ReAct\",\"limit\":1}");

        when(modelInvocations.beginModelInvocation(run))
                .thenReturn(
                        new ModelInvocationCoordinator.ModelInvocationBoundary(1, 1),
                        new ModelInvocationCoordinator.ModelInvocationBoundary(2, 3),
                        new ModelInvocationCoordinator.ModelInvocationBoundary(3, 6));
        when(contextRequests.create(eq(run), any(), eq(registry.modelDefinitions())))
                .thenAnswer(
                        invocationCall ->
                                request(
                                        run,
                                        invocationCall
                                                .getArgument(
                                                        1,
                                                        ModelInvocationCoordinator
                                                                .ModelInvocationBoundary.class)
                                                .inputUpToConversationMessageSequence(),
                                        registry.modelDefinitions()));
        when(contexts.prepare(any()))
                .thenAnswer(
                        invocationCall -> {
                            ConversationContextRequest request = invocationCall.getArgument(0);
                            return new PreparedConversationContext(
                                    "test",
                                    List.of(ContextMessage.user("question")),
                                    request.budget().fixedInputTokens(),
                                    false,
                                    0L);
                        });
        when(model.complete(any(), any(), any()))
                .thenAnswer(
                        ignored ->
                                switch (invocation.incrementAndGet()) {
                                    case 1 -> new ModelInvocationResult.ToolCalls(List.of(first));
                                    case 2 ->
                                            new ModelInvocationResult.ToolCalls(
                                                    List.of(second, failed));
                                    default ->
                                            new ModelInvocationResult.FinalAnswer("final answer");
                                });
        when(tools.executeModelInvocationTools(run, 1))
                .thenReturn(
                        List.of(
                                ToolExecutionResult.succeeded(
                                        JsonMapper.builder().build().readTree("{}"))));
        when(tools.executeModelInvocationTools(run, 2))
                .thenReturn(
                        List.of(
                                ToolExecutionResult.succeeded(
                                        JsonMapper.builder().build().readTree("{}")),
                                ToolExecutionResult.failed("business", "failed")));

        new ReactExecutionEngine(
                        model,
                        contexts,
                        contextRequests,
                        contextMessages,
                        modelInvocations,
                        tools,
                        registry,
                        leases,
                        limits)
                .execute(run);

        assertThat(invocation).hasValue(3);
        verify(model, times(3)).complete(any(), eq(registry.modelDefinitions()), any());
        verify(modelInvocations)
                .archiveToolCalls(
                        run,
                        new ModelInvocationCoordinator.ModelInvocationBoundary(1, 1),
                        List.of(first));
        verify(modelInvocations)
                .archiveToolCalls(
                        run,
                        new ModelInvocationCoordinator.ModelInvocationBoundary(2, 3),
                        List.of(second, failed));
        verify(modelInvocations)
                .completeFinalAnswer(
                        run,
                        new ModelInvocationCoordinator.ModelInvocationBoundary(3, 6),
                        "final answer");
        verify(leases, times(9)).assertOwnedAndWithinBudget(run);
        verify(leases, times(3)).cancelIfRequested(run);
        verify(contexts, times(3))
                .prepare(
                        argThat(
                                request ->
                                        request.execution().inputUpToMessageSequence() > 0
                                                && request.toolDefinitions().size() == 3
                                                && request.budget().contextWindowTokens()
                                                        == 65_536));
    }

    private static ModelToolCall call(String id, String name, String arguments) {
        return new ModelToolCall(id, name, JsonMapper.builder().build().readTree(arguments));
    }

    private static ConversationContextRequest request(
            AgentExecutionPort.ClaimedAgentExecution run,
            long inputBoundary,
            List<com.datagent.agentv2.conversation.model.ModelToolDefinition> tools) {
        return new ConversationContextRequest(
                new ContextExecutionRef(
                        run.tenantId(),
                        "session-react",
                        run.executionId(),
                        run.ownerInstanceId(),
                        run.executionAttemptNumber(),
                        inputBoundary),
                new ContextBudget(65_536, 4_096, 4_096, 128),
                new ContextPolicySnapshot(1, 5, 4, 10, 75, 65, 100, 109, 10),
                List.of(),
                tools.stream()
                        .map(
                                tool ->
                                        new ContextToolDefinition(
                                                tool.name(), tool.description(), tool.parameters()))
                        .toList());
    }
}
