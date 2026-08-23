package com.datagent.agentv2.conversation.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.datagent.agentv2.conversation.process.ConversationProcessCoordinator;
import com.datagent.agentv2.conversation.react.ModelToolCall;
import com.datagent.agentv2.conversation.runtime.LocalAgentExecution;
import com.datagent.agentv2.conversation.runtime.LocalExecutionGuard;
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

        try (ToolExecutionCoordinator coordinator =
                new ToolExecutionCoordinator(
                        new AgentToolRegistry(List.of(tool)),
                        guard,
                        processes,
                        new ToolFailureSanitizer(json))) {
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
