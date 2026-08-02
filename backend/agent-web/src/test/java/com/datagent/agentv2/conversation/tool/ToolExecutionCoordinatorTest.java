package com.datagent.agentv2.conversation.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.runtime.AgentExecutionLeaseService;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.persistence.entity.ToolExecutionEntity;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.persistence.service.ToolExecutionService;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ToolExecutionCoordinatorTest {

    private static final String EXECUTION_ID = "execution-tools";
    private static final String ASSISTANT_ID = "assistant-tools";
    private static final String SESSION_ID = "session-tools";
    private static final String TOOL_CALLS =
            """
            {"version":1,"calls":[
              {"id":"call-success","name":"success","arguments":{}},
              {"id":"call-arguments","name":"arguments","arguments":{}},
              {"id":"call-denied","name":"denied","arguments":{}},
              {"id":"call-timeout","name":"timeout","arguments":{}},
              {"id":"call-exception","name":"exception","arguments":{}},
              {"id":"call-business","name":"business","arguments":{}}
            ]}
            """;

    @Test
    void persistsSanitizedObservationForEveryOutcomeAndContinuesAfterFailures() {
        JsonMapper json = JsonMapper.builder().build();
        ToolExecutionService toolExecutions = mock(ToolExecutionService.class);
        ConversationMessageService messages = mock(ConversationMessageService.class);
        ConversationSessionService sessions = mock(ConversationSessionService.class);
        AgentExecutionLeaseService leases = mock(AgentExecutionLeaseService.class);
        IdGenerator ids = mock(IdGenerator.class);
        List<ToolExecutionEntity> rows = toolExecutions();
        List<ConversationMessage> observations = new ArrayList<>();
        AtomicInteger conversationMessageSequence = new AtomicInteger(10);
        AtomicInteger conversationMessageId = new AtomicInteger();
        ConversationMessage assistant =
                ConversationMessage.assistant(
                        ASSISTANT_ID, SESSION_ID, EXECUTION_ID, "", 1L, "assistant", "internal");
        assistant.attachToolCalls(TOOL_CALLS);

        when(toolExecutions.findByTurn(EXECUTION_ID, 1)).thenReturn(rows);
        for (ToolExecutionEntity row : rows) {
            when(toolExecutions.findByToolExecutionId(row.getToolExecutionId())).thenReturn(row);
        }
        when(toolExecutions.startExecution(
                        anyString(), anyLong(), any(), any(), anyString(), anyLong()))
                .thenAnswer(
                        invocation -> {
                            String id = invocation.getArgument(0);
                            ToolExecutionEntity row =
                                    rows.stream()
                                            .filter(value -> value.getToolExecutionId().equals(id))
                                            .findFirst()
                                            .orElseThrow();
                            row.setStatus("executing");
                            row.setExecutionCount(row.getExecutionCount() + 1);
                            row.setVersion(row.getVersion() + 1);
                            return true;
                        });
        when(toolExecutions.completeExecution(
                        anyString(),
                        anyLong(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        anyLong()))
                .thenReturn(true);
        when(messages.findByConversationMessageId(ASSISTANT_ID)).thenReturn(assistant);
        when(messages.save(any(ConversationMessage.class)))
                .thenAnswer(
                        invocation -> {
                            observations.add(invocation.getArgument(0));
                            return true;
                        });
        when(sessions.nextConversationMessageSequence(SESSION_ID))
                .thenAnswer(ignored -> (long) conversationMessageSequence.incrementAndGet());
        when(ids.next(anyString()))
                .thenAnswer(ignored -> "observation-" + conversationMessageId.incrementAndGet());

        ToolExecutionCoordinator coordinator =
                new ToolExecutionCoordinator(
                        toolExecutions,
                        leases,
                        new ToolExecutionTransactionService(
                                new AgentToolRegistry(tools(json)),
                                toolExecutions,
                                messages,
                                sessions,
                                ids,
                                json),
                        new ToolFailureSanitizer(json));
        try {
            List<ToolExecutionResult> result =
                    coordinator.executeModelInvocationTools(
                            new AgentExecutionPort.ClaimedAgentExecution(
                                    "tenant-tools", EXECUTION_ID, "owner", 1),
                            1);

            assertThat(result).hasSize(6);
            assertThat(result)
                    .extracting(ToolExecutionResult::status)
                    .containsExactly(
                            ToolExecutionResult.Status.SUCCEEDED,
                            ToolExecutionResult.Status.FAILED,
                            ToolExecutionResult.Status.FAILED,
                            ToolExecutionResult.Status.FAILED,
                            ToolExecutionResult.Status.FAILED,
                            ToolExecutionResult.Status.FAILED);
            assertThat(result)
                    .extracting(ToolExecutionResult::failureCode)
                    .containsExactly(
                            null,
                            "invalid_arguments",
                            "access_denied",
                            "timeout",
                            "tool_error",
                            "business_failure");
            assertThat(observations).hasSize(6);
            assertThat(observations).allMatch(value -> "tool".equals(value.getRole()));
            assertThat(observations).allMatch(value -> "internal".equals(value.getVisibility()));
            assertThat(observations)
                    .noneMatch(value -> value.getContent().contains("secret-token"));
        } finally {
            coordinator.close();
        }
    }

    private static List<AgentTool> tools(JsonMapper json) {
        return List.of(
                tool("success", Duration.ofSeconds(1), arguments -> json.readTree("{\"ok\":true}")),
                tool(
                        "arguments",
                        Duration.ofSeconds(1),
                        arguments -> {
                            throw new IllegalArgumentException("bad input");
                        }),
                tool(
                        "denied",
                        Duration.ofSeconds(1),
                        arguments -> {
                            throw new SecurityException("restricted");
                        }),
                tool(
                        "timeout",
                        Duration.ofMillis(5),
                        arguments -> {
                            Thread.sleep(100);
                            return json.readTree("{}");
                        }),
                tool(
                        "exception",
                        Duration.ofSeconds(1),
                        arguments -> {
                            throw new IllegalStateException("secret-token");
                        }),
                tool(
                        "business",
                        Duration.ofSeconds(1),
                        arguments -> {
                            throw new AgentTool.BusinessFailure(
                                    "business_failure", "not available");
                        }));
    }

    private static AgentTool tool(String name, Duration timeout, ToolAction action) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public JsonNode parametersSchema() {
                return JsonMapper.builder().build().readTree("{\"type\":\"object\"}");
            }

            @Override
            public Duration timeout() {
                return timeout;
            }

            @Override
            public JsonNode execute(JsonNode arguments, InvocationContext context)
                    throws Exception {
                return action.execute(arguments);
            }
        };
    }

    private static List<ToolExecutionEntity> toolExecutions() {
        List<String> names =
                List.of("success", "arguments", "denied", "timeout", "exception", "business");
        List<ToolExecutionEntity> rows = new ArrayList<>();
        for (String name : names) {
            ToolExecutionEntity row = new ToolExecutionEntity();
            row.setToolExecutionId("execution-" + name);
            row.setExecutionId(EXECUTION_ID);
            row.setModelInvocationSequence(1L);
            row.setExecutionAttemptNumber(1L);
            row.setAssistantConversationMessageId(ASSISTANT_ID);
            row.setToolCallId("call-" + name);
            row.setToolName(name);
            row.setStatus("prepared");
            row.setExecutionCount(0);
            row.setVersion(0L);
            rows.add(row);
        }
        return rows;
    }

    @FunctionalInterface
    private interface ToolAction {
        JsonNode execute(JsonNode arguments) throws Exception;
    }
}
