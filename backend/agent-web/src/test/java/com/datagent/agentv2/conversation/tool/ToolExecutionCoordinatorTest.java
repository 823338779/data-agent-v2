package com.datagent.agentv2.conversation.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.conversation.domain.BoundedProcessPayload;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.ThinkingProcessItem;
import com.datagent.agentv2.conversation.domain.ThinkingProcessStatus;
import com.datagent.agentv2.conversation.domain.ToolThinkingProcessItem;
import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.conversation.process.ConversationProcessCoordinator;
import com.datagent.agentv2.conversation.runtime.AgentExecutionLeaseService;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.persistence.entity.ToolExecutionEntity;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ToolExecutionService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ToolExecutionCoordinatorTest {

    private static final String EXECUTION_ID = "execution-tools";
    private static final String ASSISTANT_ID = "assistant-tools";
    private static final String SESSION_ID = "session-tools";
    private static final String EMPTY_JSON_SHA256 =
            "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";
    private static final String JSON_CONTENT_TYPE = "application/json";

    @Test
    void persistsEveryOutcomeThroughTheBoundToolProcessItemAndContinuesAfterFailures() {
        JsonMapper json = JsonMapper.builder().build();
        ToolExecutionService toolExecutions = mock(ToolExecutionService.class);
        ConversationMessageService messages = mock(ConversationMessageService.class);
        ConversationProcessCoordinator processes = mock(ConversationProcessCoordinator.class);
        AgentExecutionLeaseService leases = mock(AgentExecutionLeaseService.class);
        List<ToolExecutionEntity> rows = toolExecutions();
        ConversationMessage assistant = assistantWithPreparedTools(rows);

        when(toolExecutions.findByTurn(EXECUTION_ID, 1L)).thenReturn(rows);
        for (ToolExecutionEntity row : rows) {
            when(toolExecutions.findByToolExecutionId(row.getToolExecutionId())).thenReturn(row);
        }
        when(toolExecutions.startExecution(
                        anyString(), anyLong(), any(), any(), anyString(), anyLong()))
                .thenAnswer(
                        invocation -> {
                            ToolExecutionEntity row = row(rows, invocation.getArgument(0));
                            row.setStatus("executing");
                            row.setExecutionCount(row.getExecutionCount() + 1);
                            row.setVersion(row.getVersion() + 1);
                            return true;
                        });
        when(toolExecutions.completeExecution(
                        anyString(), anyLong(), anyString(), any(), any(), anyString(), anyLong()))
                .thenReturn(true);
        when(messages.findByConversationMessageId(ASSISTANT_ID)).thenReturn(assistant);

        ToolExecutionCoordinator coordinator =
                new ToolExecutionCoordinator(
                        toolExecutions,
                        leases,
                        new ToolExecutionTransactionService(
                                new AgentToolRegistry(tools(json)),
                                toolExecutions,
                                messages,
                                processes,
                                json),
                        new ToolFailureSanitizer(json));
        try {
            AgentExecutionPort.ClaimedAgentExecution run =
                    new AgentExecutionPort.ClaimedAgentExecution(
                            "tenant-tools", EXECUTION_ID, "owner", 1L);
            List<ToolExecutionResult> result = coordinator.executeModelInvocationTools(run, 1L);

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

            // 每个结果都通过 UUID 绑定的 TOOL ProcessItem 收敛，不再创建 observation 消息。
            ArgumentCaptor<ToolExecutionResult> persisted =
                    ArgumentCaptor.forClass(ToolExecutionResult.class);
            for (ToolExecutionEntity row : rows) {
                verify(processes)
                        .startTool(
                                any(),
                                org.mockito.ArgumentMatchers.eq(row.getToolProcessItemId()),
                                any());
                verify(processes)
                        .finishTool(
                                any(),
                                org.mockito.ArgumentMatchers.eq(row.getToolProcessItemId()),
                                persisted.capture(),
                                any());
            }
            assertThat(persisted.getAllValues())
                    .noneMatch(
                            value ->
                                    value.failureMessage() != null
                                            && value.failureMessage().contains("secret-token"));
        } finally {
            coordinator.close();
        }
    }

    private static ConversationMessage assistantWithPreparedTools(List<ToolExecutionEntity> rows) {
        ConversationMessage assistant =
                ConversationMessage.assistant(
                        ASSISTANT_ID, SESSION_ID, EXECUTION_ID, "", 1L, "assistant");
        Instant createdAt = Instant.parse("2026-08-04T00:00:00Z");
        for (ToolExecutionEntity row : rows) {
            assistant.appendProcessItem(
                    new ToolThinkingProcessItem(
                            row.getToolProcessItemId(),
                            assistant.nextProcessSequence(),
                            ThinkingProcessStatus.PREPARED,
                            createdAt,
                            null,
                            null,
                            null,
                            ThinkingProcessItem.CURRENT_SCHEMA_VERSION,
                            row.getToolName(),
                            row.getToolName(),
                            row.getToolExecutionId(),
                            row.getToolCallId(),
                            row.getModelInvocationSequence(),
                            emptyInput(),
                            null,
                            null,
                            null,
                            null));
        }
        return assistant;
    }

    private static BoundedProcessPayload emptyInput() {
        return new BoundedProcessPayload(
                Map.of(), null, false, 2L, EMPTY_JSON_SHA256, JSON_CONTENT_TYPE);
    }

    private static ToolExecutionEntity row(List<ToolExecutionEntity> rows, String toolExecutionId) {
        return rows.stream()
                .filter(value -> value.getToolExecutionId().equals(toolExecutionId))
                .findFirst()
                .orElseThrow();
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
                            Thread.sleep(100L);
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
                            throw new ToolBusinessException("business_failure", "not available");
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
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            ToolExecutionEntity row = new ToolExecutionEntity();
            row.setToolExecutionId("execution-" + name);
            row.setToolProcessItemId(new UUID(0L, index + 1L));
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
