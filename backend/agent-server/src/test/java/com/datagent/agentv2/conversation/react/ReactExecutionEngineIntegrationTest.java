package com.datagent.agentv2.conversation.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        ConversationContextLoader contexts = mock(ConversationContextLoader.class);
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
        when(contexts.load("execution-react", 1))
                .thenReturn(List.of(ModelInputMessage.plain("user", "question")));
        when(contexts.load("execution-react", 3))
                .thenReturn(
                        List.of(
                                ModelInputMessage.plain("user", "question"),
                                ModelInputMessage.assistantToolCalls(List.of(first)),
                                ModelInputMessage.tool(
                                        "success-one", "call-1", "system_timezone")));
        when(contexts.load("execution-react", 6))
                .thenReturn(
                        List.of(
                                ModelInputMessage.plain("user", "question"),
                                ModelInputMessage.assistantToolCalls(List.of(first)),
                                ModelInputMessage.tool("success-one", "call-1", "system_timezone"),
                                ModelInputMessage.assistantToolCalls(List.of(second, failed)),
                                ModelInputMessage.tool("success-two", "call-2", "current_time"),
                                ModelInputMessage.tool("failed-three", "call-3", "web_search")));
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

        new ReactExecutionEngine(model, contexts, modelInvocations, tools, registry, leases, limits)
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
    }

    private static ModelToolCall call(String id, String name, String arguments) {
        return new ModelToolCall(id, name, JsonMapper.builder().build().readTree(arguments));
    }
}
