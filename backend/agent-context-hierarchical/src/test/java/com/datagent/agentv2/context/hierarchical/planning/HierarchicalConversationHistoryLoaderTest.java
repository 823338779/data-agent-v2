package com.datagent.agentv2.context.hierarchical.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.BoundedProcessPayload;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.MessageRole;
import com.datagent.agentv2.conversation.domain.ThinkingProcessItem;
import com.datagent.agentv2.conversation.domain.ThinkingProcessStatus;
import com.datagent.agentv2.conversation.domain.ToolThinkingProcessItem;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HierarchicalConversationHistoryLoaderTest {
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");
    private static final String HASH = "0".repeat(64);

    private final AgentExecutionService executions = mock(AgentExecutionService.class);
    private final ConversationMessageService messages = mock(ConversationMessageService.class);
    private final HierarchicalConversationHistoryLoader loader =
            new HierarchicalConversationHistoryLoader(
                    executions,
                    messages,
                    JsonMapper.builder().build(),
                    new ConversationTurnGrouper());

    @Test
    void rebuildsToolCallsDirectlyFromThinkingProcess() {
        var execution =
                AgentExecution.running(
                        "execution", "session", "user", "key", "hash", java.time.Instant.MAX);
        when(executions.findByExecutionId("execution")).thenReturn(execution);

        ConversationMessage user = message("user", 1L, MessageRole.USER, "weather?");
        ConversationMessage assistant =
                ConversationMessage.builder()
                        .conversationMessageId("assistant")
                        .conversationSessionId("session")
                        .executionId("previous-execution")
                        .conversationMessageSequence(2L)
                        .role(MessageRole.ASSISTANT)
                        .content("sunny")
                        .thinkingProcess(List.of(toolProcess()))
                        .build();
        when(messages.findByConversationSessionId("session")).thenReturn(List.of(user, assistant));

        List<ConversationTurn> turns =
                loader.load(new ContextExecutionRef("tenant", "session", "execution", 1L));

        assertThat(turns).singleElement();
        assertThat(turns.getFirst().messages())
                .extracting(ContextMessage::role)
                .containsExactly(
                        ContextMessage.Role.USER,
                        ContextMessage.Role.ASSISTANT,
                        ContextMessage.Role.TOOL,
                        ContextMessage.Role.ASSISTANT);
        assertThat(turns.getFirst().messages().get(1).toolCalls())
                .singleElement()
                .satisfies(
                        call -> {
                            assertThat(call.id()).isEqualTo("call-1");
                            assertThat(call.name()).isEqualTo("weather");
                        });
        verify(messages).findByConversationSessionId("session");
        verify(messages, never()).findByConversationSessionIdUpTo(anyString(), anyLong());
    }

    @Test
    void rejectsExecutionFromAnotherSessionBeforeReadingHistory() {
        var execution =
                AgentExecution.running(
                        "execution", "other", "user", "key", "hash", java.time.Instant.MAX);
        when(executions.findByExecutionId("execution")).thenReturn(execution);

        assertThatThrownBy(
                        () ->
                                loader.load(
                                        new ContextExecutionRef(
                                                "tenant", "session", "execution", 2L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Agent execution does not belong to the requested conversation session");
    }

    private static ConversationMessage message(
            String id, long sequence, MessageRole role, String content) {
        return ConversationMessage.builder()
                .conversationMessageId(id)
                .conversationSessionId("session")
                .executionId("execution")
                .conversationMessageSequence(sequence)
                .role(role)
                .content(content)
                .build();
    }

    private static ToolThinkingProcessItem toolProcess() {
        return new ToolThinkingProcessItem(
                UUID.randomUUID(),
                1L,
                ThinkingProcessStatus.SUCCEEDED,
                NOW,
                NOW,
                NOW,
                0L,
                ThinkingProcessItem.CURRENT_SCHEMA_VERSION,
                "weather",
                "call-1",
                1L,
                new BoundedProcessPayload(
                        Map.of("city", "Shanghai"), null, false, 8L, HASH, "application/json"),
                new BoundedProcessPayload(
                        Map.of("result", "sunny"), null, false, 8L, HASH, "application/json"),
                null,
                null);
    }
}
