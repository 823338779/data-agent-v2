package com.datagent.agentv2.conversation.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.BoundedProcessPayload;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.ThinkingProcessItem;
import com.datagent.agentv2.conversation.domain.ThinkingProcessStatus;
import com.datagent.agentv2.conversation.domain.ToolThinkingProcessItem;
import com.datagent.agentv2.persistence.entity.ToolExecutionEntity;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ToolExecutionService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ConversationContextLoaderIntegrationTest {

    private static final String EXECUTION_ID = "execution_context";
    private static final String SESSION_ID = "session_context";
    private static final String ASSISTANT_ID = "message-3";
    private static final UUID PROCESS_ITEM_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final String PAYLOAD_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String CONTENT_TYPE = "application/json";

    private final AgentExecutionService agentExecutions = mock(AgentExecutionService.class);
    private final ConversationMessageService messages = mock(ConversationMessageService.class);
    private final ToolExecutionService toolExecutions = mock(ToolExecutionService.class);
    private final ConversationContextLoader loader =
            new ConversationContextLoader(
                    agentExecutions, messages, toolExecutions, JsonMapper.builder().build());

    @Test
    void rebuildsRealMessagesAndToolProtocolFromTheAssistantCenterInSequence() {
        stubExecution();
        ConversationMessage assistant = message(3L, "assistant", "final");
        assistant.setConversationMessageId(ASSISTANT_ID);
        assistant.setThinkingProcess(List.of(terminalTool(PROCESS_ITEM_ID, "call-1")));
        when(messages.findByConversationSessionIdUpTo(SESSION_ID, 3L))
                .thenReturn(
                        List.of(
                                message(1L, "system", "system"),
                                message(2L, "user", "question"),
                                assistant));
        when(toolExecutions.findByExecutionId(EXECUTION_ID))
                .thenReturn(List.of(execution(PROCESS_ITEM_ID, "call-1")));

        List<ModelInputMessage> context = loader.load(EXECUTION_ID, 3L);

        assertThat(context)
                .extracting(ModelInputMessage::role)
                .containsExactly(
                        ModelInputMessage.Role.SYSTEM,
                        ModelInputMessage.Role.USER,
                        ModelInputMessage.Role.ASSISTANT,
                        ModelInputMessage.Role.TOOL,
                        ModelInputMessage.Role.ASSISTANT);
        assertThat(context.get(2).toolCalls())
                .containsExactly(
                        new ModelToolCall(
                                "call-1",
                                "catalog_query",
                                JsonMapper.builder().build().readTree("{\"limit\":10}")));
        assertThat(context.get(3).content()).isEqualTo("{\"status\":\"ok\"}");
        assertThat(context.get(3).toolCallId()).isEqualTo("call-1");
        assertThat(context.get(4).content()).isEqualTo("final");
    }

    @Test
    void truncatesAtInputBoundaryAndNeverReadsAnotherExecutionOnlyHistory() {
        stubExecution();
        when(messages.findByConversationSessionIdUpTo(SESSION_ID, 2L))
                .thenReturn(
                        List.of(message(1L, "system", "system"), message(2L, "user", "question")));
        when(toolExecutions.findByExecutionId(EXECUTION_ID)).thenReturn(List.of());

        assertThat(loader.load(EXECUTION_ID, 2L))
                .extracting(ModelInputMessage::content)
                .containsExactly("system", "question");
    }

    @Test
    void rejectsToolExecutionWhoseProviderCallIdDoesNotMatchItsProcessItem() {
        stubExecution();
        ConversationMessage assistant = message(1L, "assistant", "");
        assistant.setConversationMessageId(ASSISTANT_ID);
        assistant.setThinkingProcess(List.of(terminalTool(PROCESS_ITEM_ID, "call-1")));
        when(messages.findByConversationSessionIdUpTo(SESSION_ID, 1L))
                .thenReturn(List.of(assistant));
        when(toolExecutions.findByExecutionId(EXECUTION_ID))
                .thenReturn(List.of(execution(PROCESS_ITEM_ID, "wrong-call")));

        assertThatThrownBy(() -> loader.load(EXECUTION_ID, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Tool process item does not match its execution row");
    }

    private void stubExecution() {
        AgentExecution execution = new AgentExecution();
        execution.setExecutionId(EXECUTION_ID);
        execution.setConversationSessionId(SESSION_ID);
        when(agentExecutions.findByExecutionId(EXECUTION_ID)).thenReturn(execution);
    }

    private static ConversationMessage message(long sequence, String role, String content) {
        ConversationMessage message = new ConversationMessage();
        message.setConversationMessageId("message-" + sequence);
        message.setConversationSessionId(SESSION_ID);
        message.setExecutionId(EXECUTION_ID);
        message.setConversationMessageSequence(sequence);
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private static ToolThinkingProcessItem terminalTool(UUID processItemId, String callId) {
        Instant createdAt = Instant.parse("2026-08-04T00:00:00Z");
        Instant completedAt = createdAt.plusSeconds(1L);
        return new ToolThinkingProcessItem(
                processItemId,
                1L,
                ThinkingProcessStatus.SUCCEEDED,
                createdAt,
                createdAt,
                completedAt,
                1000L,
                ThinkingProcessItem.CURRENT_SCHEMA_VERSION,
                "catalog_query",
                "catalog_query",
                "execution-1",
                callId,
                1L,
                payload(Map.of("limit", 10)),
                payload(Map.of("status", "ok")),
                true,
                null,
                null);
    }

    private static BoundedProcessPayload payload(Object value) {
        return new BoundedProcessPayload(value, null, false, 1L, PAYLOAD_SHA256, CONTENT_TYPE);
    }

    private static ToolExecutionEntity execution(UUID processItemId, String callId) {
        ToolExecutionEntity execution = new ToolExecutionEntity();
        execution.setToolExecutionId("execution-1");
        execution.setToolProcessItemId(processItemId);
        execution.setAssistantConversationMessageId(ASSISTANT_ID);
        execution.setModelInvocationSequence(1L);
        execution.setToolCallId(callId);
        execution.setToolName("catalog_query");
        execution.setStatus("succeeded");
        return execution;
    }
}
