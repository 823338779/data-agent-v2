package com.datagent.agentv2.context.hierarchical.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.hierarchical.domain.ContextStepCursor;
import com.datagent.agentv2.context.hierarchical.domain.ContextStepCursor.PositionKind;
import com.datagent.agentv2.context.hierarchical.domain.ContextStepGroup;
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

class HierarchicalStepHistoryTest {
    private static final String SESSION = "session";
    private static final String EXECUTION = "execution";
    private static final String USER = "user";
    private static final String KEY = "key";
    private static final String HASH = "0".repeat(64);
    private static final String TOOL = "read";
    private static final String CALL = "call-";
    private static final String JSON = "application/json";
    private static final String EMPTY = "";
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private final AgentExecutionService executions = mock(AgentExecutionService.class);
    private final ConversationMessageService messages = mock(ConversationMessageService.class);
    private final HierarchicalConversationHistoryLoader loader =
            new HierarchicalConversationHistoryLoader(
                    executions,
                    messages,
                    JsonMapper.builder().build(),
                    new ConversationTurnGrouper());

    @Test
    void cursorOrdersWithinOneMessage() {
        var start = new ContextStepCursor(2, PositionKind.MESSAGE_START, 0);
        var first = new ContextStepCursor(2, PositionKind.INVOCATION_END, 1);
        var second = new ContextStepCursor(2, PositionKind.INVOCATION_END, 2);
        var end = new ContextStepCursor(2, PositionKind.MESSAGE_END, 0);
        assertThat(List.of(start, first, second, end)).isSorted();
        assertThat(end).isLessThan(new ContextStepCursor(3, PositionKind.MESSAGE_START, 0));
        assertThatThrownBy(() -> new ContextStepCursor(2, PositionKind.MESSAGE_END, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pendingToolGroupIsNotCompressible() {
        history(
                List.of(
                        tool(1, 1, ThinkingProcessStatus.SUCCEEDED),
                        tool(2, 1, ThinkingProcessStatus.RUNNING)));
        var group = loader.loadSteps(SESSION).getLast();
        assertThat(group.complete()).isFalse();
        assertThat(group.messages().getFirst().toolCalls()).hasSize(2);
        assertThat(group.messages()).hasSize(2);
    }

    @Test
    void appendingInvocationPreservesFrozenPrefix() {
        var first = tool(1, 1, ThinkingProcessStatus.SUCCEEDED);
        history(List.of(first));
        var frozen = loader.loadSteps(SESSION).getLast();
        history(List.of(first, tool(2, 2, ThinkingProcessStatus.SUCCEEDED)));
        var expanded = loader.loadSteps(SESSION);
        assertThat(expanded.get(1)).isEqualTo(frozen);
        assertThat(expanded.getLast().fromExclusive()).isEqualTo(frozen.throughInclusive());
        assertThat(expanded.getLast().throughInclusive()).isGreaterThan(frozen.throughInclusive());
        assertThat(expanded).allMatch(ContextStepGroup::complete);
    }

    @Test
    void terminalEmptyAssistantDoesNotHideLaterUser() {
        var execution = history(List.of());
        execution.cancel();
        when(messages.findByConversationSessionId(SESSION))
                .thenReturn(
                        List.of(
                                ConversationMessage.user(USER, SESSION, USER, 1, USER),
                                ConversationMessage.assistantPlaceholder(
                                        EXECUTION, SESSION, EXECUTION, 2),
                                ConversationMessage.user(KEY, SESSION, KEY, 3, USER)));
        var groups = loader.loadSteps(SESSION);
        assertThat(groups).hasSize(3);
        assertThat(groups.get(1).throughInclusive().positionKind())
                .isEqualTo(PositionKind.MESSAGE_END);
        assertThat(groups.getLast().messages().getFirst().content()).isEqualTo(KEY);
    }

    @Test
    void sourceDigestIgnoresJsonObjectKeyOrder() {
        var first = tool(1, 1, ThinkingProcessStatus.SUCCEEDED);
        var values = new java.util.LinkedHashMap<String, Object>();
        values.put("status", "SUCCEEDED");
        values.put("queryId", "query-42");
        var payload = new BoundedProcessPayload(values, null, false, 42, HASH, JSON);
        var step =
                new ToolThinkingProcessItem(
                        first.processItemId(),
                        first.sequence(),
                        first.status(),
                        first.createdAt(),
                        first.startedAt(),
                        first.completedAt(),
                        first.durationMillis(),
                        first.schemaVersion(),
                        first.toolName(),
                        first.toolCallId(),
                        first.modelInvocationSequence(),
                        payload,
                        payload,
                        null,
                        null);
        history(List.of(step));
        var before = loader.loadSteps(SESSION).getLast().sourceDigest();
        values.clear();
        values.put("queryId", "query-42");
        values.put("status", "SUCCEEDED");
        assertThat(loader.loadSteps(SESSION).getLast().sourceDigest()).isEqualTo(before);
    }

    private AgentExecution history(List<ThinkingProcessItem> process) {
        var execution = AgentExecution.running(EXECUTION, SESSION, USER, KEY, HASH, Instant.MAX);
        when(executions.findByExecutionId(EXECUTION)).thenReturn(execution);
        when(messages.findByConversationSessionId(SESSION))
                .thenReturn(
                        List.of(
                                ConversationMessage.user(USER, SESSION, USER, 1, USER),
                                ConversationMessage.builder()
                                        .conversationMessageId(EXECUTION)
                                        .conversationSessionId(SESSION)
                                        .executionId(EXECUTION)
                                        .conversationMessageSequence(2L)
                                        .role(MessageRole.ASSISTANT)
                                        .content(EMPTY)
                                        .thinkingProcess(process)
                                        .build()));
        return execution;
    }

    private static ToolThinkingProcessItem tool(
            long sequence, long invocation, ThinkingProcessStatus status) {
        boolean terminal = status == ThinkingProcessStatus.SUCCEEDED;
        var payload = new BoundedProcessPayload(Map.of(KEY, USER), null, false, 10, HASH, JSON);
        return new ToolThinkingProcessItem(
                UUID.randomUUID(),
                sequence,
                status,
                NOW,
                NOW,
                terminal ? NOW : null,
                terminal ? 0L : null,
                ThinkingProcessItem.CURRENT_SCHEMA_VERSION,
                TOOL,
                CALL + sequence,
                invocation,
                payload,
                terminal ? payload : null,
                null,
                null);
    }
}
