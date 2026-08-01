package com.datagent.agentv2.conversation.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.conversation.runtime.RunExecutionPort;
import com.datagent.agentv2.conversation.runtime.RunLeaseService;
import com.datagent.agentv2.persistence.entity.AgentMessageEntity;
import com.datagent.agentv2.persistence.entity.AgentToolExecutionEntity;
import com.datagent.agentv2.persistence.service.AgentMessageService;
import com.datagent.agentv2.persistence.service.AgentSessionService;
import com.datagent.agentv2.persistence.service.AgentToolExecutionService;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ToolExecutionCoordinatorTest {

    private static final String RUN_ID = "run-tools";
    private static final String ASSISTANT_ID = "assistant-tools";
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
        AgentToolExecutionService executions = mock(AgentToolExecutionService.class);
        AgentMessageService messages = mock(AgentMessageService.class);
        AgentSessionService sessions = mock(AgentSessionService.class);
        RunLeaseService leases = mock(RunLeaseService.class);
        IdGenerator ids = mock(IdGenerator.class);
        PlatformTransactionManager transactionManager = transactionManager();
        List<AgentToolExecutionEntity> rows = executions();
        List<AgentMessageEntity> observations = new ArrayList<>();
        AtomicInteger messageSequence = new AtomicInteger(10);
        AtomicInteger messageId = new AtomicInteger();
        AgentMessageEntity assistant = new AgentMessageEntity();
        assistant.setMessageId(ASSISTANT_ID);
        assistant.setSessionId("session-tools");
        assistant.setToolCalls(TOOL_CALLS);

        when(executions.findByTurn(RUN_ID, 1)).thenReturn(rows);
        for (AgentToolExecutionEntity row : rows) {
            when(executions.findByToolExecutionId(row.getToolExecutionId())).thenReturn(row);
        }
        when(executions.startExecution(
                        anyString(), anyLong(), any(), any(), anyString(), anyLong()))
                .thenAnswer(
                        invocation -> {
                            String id = invocation.getArgument(0);
                            AgentToolExecutionEntity row =
                                    rows.stream()
                                            .filter(value -> value.getToolExecutionId().equals(id))
                                            .findFirst()
                                            .orElseThrow();
                            row.setStatus("executing");
                            row.setExecutionCount(row.getExecutionCount() + 1);
                            row.setVersion(row.getVersion() + 1);
                            return true;
                        });
        when(executions.completeExecution(
                        anyString(),
                        anyLong(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        anyLong()))
                .thenReturn(true);
        when(messages.findByMessageId(ASSISTANT_ID)).thenReturn(assistant);
        when(messages.save(any()))
                .thenAnswer(
                        invocation -> {
                            observations.add(invocation.getArgument(0));
                            return true;
                        });
        when(sessions.nextMessageSequence("session-tools"))
                .thenAnswer(ignored -> (long) messageSequence.incrementAndGet());
        when(ids.next(anyString()))
                .thenAnswer(ignored -> "observation-" + messageId.incrementAndGet());

        ToolExecutionCoordinator coordinator =
                new ToolExecutionCoordinator(
                        new AgentToolRegistry(tools(json)),
                        executions,
                        messages,
                        sessions,
                        leases,
                        ids,
                        new ToolFailureSanitizer(json),
                        json,
                        transactionManager);
        try {
            List<ToolObservation> result =
                    coordinator.executeTurn(
                            new RunExecutionPort.OwnedRun("tenant-tools", RUN_ID, "owner", 1), 1);

            assertThat(result).hasSize(6);
            assertThat(result)
                    .extracting(ToolObservation::status)
                    .containsExactly(
                            ToolObservation.Status.SUCCEEDED,
                            ToolObservation.Status.FAILED,
                            ToolObservation.Status.FAILED,
                            ToolObservation.Status.FAILED,
                            ToolObservation.Status.FAILED,
                            ToolObservation.Status.FAILED);
            assertThat(result)
                    .extracting(ToolObservation::failureCode)
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

    private static List<AgentToolExecutionEntity> executions() {
        List<String> names =
                List.of("success", "arguments", "denied", "timeout", "exception", "business");
        List<AgentToolExecutionEntity> rows = new ArrayList<>();
        for (String name : names) {
            AgentToolExecutionEntity row = new AgentToolExecutionEntity();
            row.setToolExecutionId("execution-" + name);
            row.setRunId(RUN_ID);
            row.setModelTurnSequence(1L);
            row.setExecutionAttempt(1L);
            row.setAssistantMessageId(ASSISTANT_ID);
            row.setToolCallId("call-" + name);
            row.setToolName(name);
            row.setStatus("prepared");
            row.setExecutionCount(0);
            row.setVersion(0L);
            rows.add(row);
        }
        return rows;
    }

    private static PlatformTransactionManager transactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
        return manager;
    }

    @FunctionalInterface
    private interface ToolAction {
        JsonNode execute(JsonNode arguments) throws Exception;
    }
}
