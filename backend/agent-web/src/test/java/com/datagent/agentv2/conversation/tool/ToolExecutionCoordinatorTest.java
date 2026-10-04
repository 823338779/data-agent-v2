package com.datagent.agentv2.conversation.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.datagent.agentv2.conversation.process.ConversationProcessCoordinator;
import com.datagent.agentv2.conversation.react.ModelToolCall;
import com.datagent.agentv2.conversation.runtime.LocalAgentExecution;
import com.datagent.agentv2.conversation.runtime.LocalExecutionGuard;
import com.datagent.agentv2.conversation.tool.runtime.ExecutionToolViews;
import com.datagent.agentv2.conversation.tool.runtime.ToolPolicyProperties;
import com.datagent.agentv2.conversation.tool.supply.ToolSupplyRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ToolExecutionCoordinatorTest {

    @Test
    void executesPreparedToolAndPersistsProcessTransitions() {
        JsonMapper json = JsonMapper.builder().build();
        AgentTool tool = new EchoTool(json);
        ConversationProcessCoordinator processes = mock(ConversationProcessCoordinator.class);
        LocalExecutionGuard guard = mock(LocalExecutionGuard.class);
        UUID processItemId = UUID.randomUUID();
        LocalAgentExecution run = new LocalAgentExecution("tenant", "session", "execution", 2L);
        PreparedToolInvocation prepared =
                new PreparedToolInvocation(
                        processItemId,
                        new ModelToolCall("call", "echo", json.createObjectNode().put("x", 1)));

        ExecutionToolViews toolViews =
                new ExecutionToolViews(
                        new ToolSupplyRegistry(List.of(tool)),
                        new ToolPolicyProperties(List.of("echo"), List.of("echo")));
        toolViews.open(run.executionId());
        try (ToolExecutionCoordinator coordinator =
                new ToolExecutionCoordinator(
                        toolViews, guard, processes, new ToolFailureSanitizer(json))) {
            List<ToolExecutionResult> results =
                    coordinator.executeModelInvocationTools(run, 1L, List.of(prepared));

            assertThat(results)
                    .singleElement()
                    .extracting(ToolExecutionResult::status)
                    .isEqualTo(ToolExecutionResult.Status.SUCCEEDED);
            verify(processes)
                    .startTool(
                            org.mockito.ArgumentMatchers.eq(run),
                            org.mockito.ArgumentMatchers.eq(processItemId),
                            org.mockito.ArgumentMatchers.any());
            verify(processes)
                    .finishTool(
                            org.mockito.ArgumentMatchers.eq(run),
                            org.mockito.ArgumentMatchers.eq(processItemId),
                            org.mockito.ArgumentMatchers.any(ToolExecutionResult.class),
                            org.mockito.ArgumentMatchers.any());
        }
    }

    @Test
    void archiveFailureNeverReexecutesSuccessfulTool() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        AgentTool tool = org.mockito.Mockito.spy(new EchoTool(json));
        var processes = mock(ConversationProcessCoordinator.class);
        var run = new LocalAgentExecution("tenant", "session", "execution", 2L);
        var id = UUID.randomUUID();
        var invocation =
                new PreparedToolInvocation(
                        id, new ModelToolCall("call", "echo", json.createObjectNode()));
        var views =
                new ExecutionToolViews(
                        new ToolSupplyRegistry(List.of(tool)),
                        new ToolPolicyProperties(List.of("echo"), List.of("echo")));
        views.open(run.executionId());
        org.mockito.Mockito.doThrow(new IllegalStateException("archive failed"))
                .when(processes)
                .finishTool(
                        org.mockito.ArgumentMatchers.eq(run),
                        org.mockito.ArgumentMatchers.eq(id),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        try (var coordinator =
                new ToolExecutionCoordinator(
                        views,
                        mock(LocalExecutionGuard.class),
                        processes,
                        new ToolFailureSanitizer(json))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    coordinator.executeModelInvocationTools(
                                            run, 1L, List.of(invocation)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("archive failed");
            verify(tool)
                    .execute(
                            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }
    }

    private record EchoTool(JsonMapper json) implements AgentTool {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "echo";
        }

        @Override
        public JsonNode parametersSchema() {
            return json.createObjectNode();
        }

        @Override
        public Duration timeout() {
            return Duration.ofSeconds(1);
        }

        @Override
        public JsonNode execute(JsonNode arguments, InvocationContext context) {
            return arguments;
        }
    }
}
