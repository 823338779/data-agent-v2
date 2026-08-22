package com.datagent.agentv2.conversation.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.conversation.context.ContextBudget;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextModelMessageMapper;
import com.datagent.agentv2.conversation.context.ContextStrategyProperties;
import com.datagent.agentv2.conversation.context.ContextToolCall;
import com.datagent.agentv2.conversation.context.ContextToolDefinition;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import com.datagent.agentv2.conversation.context.ConversationContextRequestFactory;
import com.datagent.agentv2.conversation.context.ConversationContextStrategy;
import com.datagent.agentv2.conversation.context.ConversationContextStrategyResolver;
import com.datagent.agentv2.conversation.context.PreparedConversationContext;
import com.datagent.agentv2.conversation.model.api.AgentModelGateway;
import com.datagent.agentv2.conversation.model.api.ModelInvocationResult;
import com.datagent.agentv2.conversation.model.api.ModelToolDefinition;
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
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class ReactExecutionEngineIntegrationTest {

    private static final String FIXED_FRAMEWORK = "fixed framework instructions";

    @Test
    void runsRegisteredBuiltInToolsAcrossThreeModelInvocationsAndCompletesFinalAnswer() {
        JsonMapper json = JsonMapper.builder().build();
        AgentModelGateway model = mock(AgentModelGateway.class);
        ConversationContextStrategyResolver contexts = contextResolver();
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
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModelInputMessage>> modelMessages = ArgumentCaptor.forClass(List.class);
        verify(model, times(3))
                .complete(modelMessages.capture(), eq(registry.modelDefinitions()), any());
        assertThat(modelMessages.getAllValues().getFirst())
                .containsExactly(
                        new ModelInputMessage(
                                ModelInputMessage.Role.SYSTEM,
                                FIXED_FRAMEWORK,
                                null,
                                List.of(),
                                null,
                                null),
                        new ModelInputMessage(
                                ModelInputMessage.Role.USER,
                                "question",
                                null,
                                List.of(),
                                null,
                                null),
                        new ModelInputMessage(
                                ModelInputMessage.Role.ASSISTANT,
                                "",
                                "thinking-state",
                                List.of(call("history-call", "system_timezone", "{}")),
                                null,
                                null),
                        new ModelInputMessage(
                                ModelInputMessage.Role.TOOL,
                                "UTC+08:00",
                                null,
                                List.of(),
                                "history-call",
                                "system_timezone"));
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
    }

    private static ConversationContextStrategyResolver contextResolver() {
        ContextStrategyProperties properties = new ContextStrategyProperties();
        properties.setStrategy("test");
        ConversationContextStrategy strategy =
                new ConversationContextStrategy() {
                    @Override
                    public String strategyId() {
                        return "test";
                    }

                    @Override
                    public PreparedConversationContext prepare(ConversationContextRequest request) {
                        assertThat(request.fixedSystemMessages())
                                .containsExactly(ContextMessage.system(FIXED_FRAMEWORK));
                        assertThat(request.toolDefinitions()).hasSize(3);
                        return new PreparedConversationContext(
                                "test",
                                List.of(
                                        ContextMessage.system(FIXED_FRAMEWORK),
                                        ContextMessage.user("question"),
                                        new ContextMessage(
                                                ContextMessage.Role.ASSISTANT,
                                                "",
                                                "thinking-state",
                                                List.of(
                                                        new ContextToolCall(
                                                                "history-call",
                                                                "system_timezone",
                                                                java.util.Map.of())),
                                                null,
                                                null),
                                        ContextMessage.tool(
                                                "UTC+08:00", "history-call", "system_timezone")),
                                request.budget().fixedInputTokens(),
                                false,
                                0L);
                    }
                };
        return new ConversationContextStrategyResolver(List.of(strategy), properties);
    }

    private static ModelToolCall call(String id, String name, String arguments) {
        return new ModelToolCall(id, name, JsonMapper.builder().build().readTree(arguments));
    }

    private static ConversationContextRequest request(
            AgentExecutionPort.ClaimedAgentExecution run,
            long inputBoundary,
            List<ModelToolDefinition> tools) {
        return new ConversationContextRequest(
                new ContextExecutionRef(
                        run.tenantId(),
                        "session-react",
                        run.executionId(),
                        run.ownerInstanceId(),
                        run.executionAttemptNumber(),
                        inputBoundary),
                new ContextBudget(65_536, 4_096, 4_096, 128),
                List.of(ContextMessage.system(FIXED_FRAMEWORK)),
                tools.stream()
                        .map(
                                tool ->
                                        new ContextToolDefinition(
                                                tool.name(), tool.description(), tool.parameters()))
                        .toList());
    }
}
