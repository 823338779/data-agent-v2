package com.datagent.agentv2.context.hierarchical.planning;

import static com.datagent.agentv2.conversation.context.ContextMessage.Role.ASSISTANT;
import static com.datagent.agentv2.conversation.context.ContextMessage.Role.TOOL;
import static com.datagent.agentv2.conversation.context.ContextMessage.Role.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.BoundedProcessPayload;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.ModelThinkingProcessItem;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

class HierarchicalConversationHistoryLoaderTest {

    private static final String TENANT_ID = "tenant";
    private static final String SESSION_ID = "session";
    private static final String OTHER_SESSION_ID = "other-session";
    private static final String EXECUTION_ID = "execution-current";
    private static final String PREVIOUS_EXECUTION_ID = "execution-previous";
    private static final String OWNER_ID = "owner";
    private static final String USER_MESSAGE_ID = "message-user";
    private static final String ASSISTANT_MESSAGE_ID = "message-assistant";
    private static final String QUESTION = "Find weather and time";
    private static final String FINAL_ANSWER = "Sunny at noon.";
    private static final String FIRST_THINKING = "Need weather.";
    private static final String SECOND_THINKING = "Need local time.";
    private static final String FINAL_THINKING = "Compose answer.";
    private static final String FIRST_TOOL_EXECUTION_ID = "tool-execution-1";
    private static final String SECOND_TOOL_EXECUTION_ID = "tool-execution-2";
    private static final String FIRST_TOOL_CALL_ID = "call-weather";
    private static final String SECOND_TOOL_CALL_ID = "call-time";
    private static final String FIRST_TOOL_NAME = "weather";
    private static final String SECOND_TOOL_NAME = "clock";
    private static final String ARGUMENT_KEY = "city";
    private static final String ARGUMENT_VALUE = "Shanghai";
    private static final String RESULT_KEY = "result";
    private static final String FIRST_RESULT = "sunny";
    private static final String SECOND_RESULT = "12:00";
    private static final String PREVIEW_TEXT = "{\"result\":\"sun...\"}";
    private static final String CONTENT_TYPE = "application/json";
    private static final String PAYLOAD_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String TOOL_STATUS_SUCCEEDED = "succeeded";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String MODEL_PROVIDER = "provider";
    private static final String MODEL_NAME = "model";
    private static final String KEY_PREVIEW = "preview";
    private static final String KEY_TRUNCATED = "truncated";
    private static final String WRONG_TOOL_EXECUTION_ID = "wrong-tool-execution";
    private static final String WRONG_TOOL_CALL_ID = "wrong-call";
    private static final String WRONG_TOOL_NAME = "wrong-tool";
    private static final String FIRST_PROCESS_UUID = "00000000-0000-0000-0000-000000000101";
    private static final String SECOND_PROCESS_UUID = "00000000-0000-0000-0000-000000000102";
    private static final String CREATED_AT_TEXT = "2026-08-13T00:00:00Z";
    private static final String ERROR_MISMATCHED_TOOL_PROCESS =
            "Tool process item does not match its execution row";
    private static final String ERROR_EXECUTION_SESSION_MISMATCH =
            "Agent execution does not belong to the requested conversation session";
    private static final Instant CREATED_AT = Instant.parse(CREATED_AT_TEXT);
    private static final long INPUT_BOUNDARY = 20L;

    private final AgentExecutionService agentExecutions = mock(AgentExecutionService.class);
    private final ConversationMessageService messages = mock(ConversationMessageService.class);
    private final ToolExecutionService toolExecutions = mock(ToolExecutionService.class);
    private final HierarchicalConversationHistoryLoader loader =
            new HierarchicalConversationHistoryLoader(
                    agentExecutions,
                    messages,
                    toolExecutions,
                    JsonMapper.builder().build(),
                    new ConversationTurnGrouper());

    @Test
    void reconstructsThinkingAndMultipleToolInvocationsInProviderNeutralOrder() {
        stubCurrentExecution(SESSION_ID);
        ConversationMessage assistant = assistantWithCompleteProcess();
        when(messages.findByConversationSessionIdUpTo(SESSION_ID, INPUT_BOUNDARY))
                .thenReturn(List.of(userMessage(), assistant));
        when(toolExecutions.findByExecutionIds(List.of(PREVIOUS_EXECUTION_ID)))
                .thenReturn(
                        List.of(
                                toolExecution(
                                        FIRST_TOOL_EXECUTION_ID,
                                        FIRST_TOOL_CALL_ID,
                                        FIRST_TOOL_NAME,
                                        1L,
                                        firstProcessId()),
                                toolExecution(
                                        SECOND_TOOL_EXECUTION_ID,
                                        SECOND_TOOL_CALL_ID,
                                        SECOND_TOOL_NAME,
                                        2L,
                                        secondProcessId())));

        List<ConversationTurn> turns = loader.load(executionRef());

        assertThat(turns).singleElement().extracting(ConversationTurn::completed).isEqualTo(true);
        assertThat(turns.getFirst().messages())
                .extracting(ContextMessage::role)
                .containsExactly(USER, ASSISTANT, TOOL, ASSISTANT, TOOL, ASSISTANT);
        assertThat(turns.getFirst().messages())
                .filteredOn(message -> message.role() == ASSISTANT)
                .extracting(ContextMessage::thinking)
                .containsExactly(FIRST_THINKING, SECOND_THINKING, FINAL_THINKING);
        assertThat(turns.getFirst())
                .extracting(
                        ConversationTurn::startMessageSequence,
                        ConversationTurn::endMessageSequence)
                .containsExactly(10L, INPUT_BOUNDARY);
    }

    @Test
    void usesStoredBoundedPreviewsForTruncatedToolPayloads() {
        stubCurrentExecution(SESSION_ID);
        ToolThinkingProcessItem truncated =
                toolItem(
                        firstProcessId(),
                        1L,
                        FIRST_TOOL_EXECUTION_ID,
                        FIRST_TOOL_CALL_ID,
                        FIRST_TOOL_NAME,
                        truncatedPayload(PREVIEW_TEXT),
                        truncatedPayload(PREVIEW_TEXT));
        ConversationMessage assistant = assistantMessage(List.of(truncated), FINAL_ANSWER);
        when(messages.findByConversationSessionIdUpTo(SESSION_ID, INPUT_BOUNDARY))
                .thenReturn(List.of(userMessage(), assistant));
        when(toolExecutions.findByExecutionIds(List.of(PREVIOUS_EXECUTION_ID)))
                .thenReturn(
                        List.of(
                                toolExecution(
                                        FIRST_TOOL_EXECUTION_ID,
                                        FIRST_TOOL_CALL_ID,
                                        FIRST_TOOL_NAME,
                                        1L,
                                        firstProcessId())));

        List<ContextMessage> rebuilt = loader.load(executionRef()).getFirst().messages();
        ContextMessage toolCall = rebuilt.get(1);
        ContextMessage toolResult = rebuilt.get(2);

        assertThat(toolCall.toolCalls().getFirst().arguments())
                .containsEntry(KEY_PREVIEW, PREVIEW_TEXT)
                .containsEntry(KEY_TRUNCATED, true);
        assertThat(
                        JsonMapper.builder()
                                .build()
                                .readTree(toolResult.content())
                                .path(KEY_PREVIEW)
                                .asString())
                .isEqualTo(PREVIEW_TEXT);
    }

    @ParameterizedTest
    @EnumSource(ToolMismatch.class)
    void rejectsEveryPersistedToolIdentityMismatch(ToolMismatch mismatch) {
        stubCurrentExecution(SESSION_ID);
        ToolThinkingProcessItem item =
                toolItem(
                        firstProcessId(),
                        1L,
                        FIRST_TOOL_EXECUTION_ID,
                        FIRST_TOOL_CALL_ID,
                        FIRST_TOOL_NAME,
                        completePayload(Map.of(ARGUMENT_KEY, ARGUMENT_VALUE)),
                        completePayload(Map.of(RESULT_KEY, FIRST_RESULT)));
        ConversationMessage assistant = assistantMessage(List.of(item), FINAL_ANSWER);
        ToolExecutionEntity execution =
                toolExecution(
                        FIRST_TOOL_EXECUTION_ID,
                        FIRST_TOOL_CALL_ID,
                        FIRST_TOOL_NAME,
                        1L,
                        firstProcessId());
        mismatch.apply(execution);
        when(messages.findByConversationSessionIdUpTo(SESSION_ID, INPUT_BOUNDARY))
                .thenReturn(List.of(userMessage(), assistant));
        when(toolExecutions.findByExecutionIds(List.of(PREVIOUS_EXECUTION_ID)))
                .thenReturn(List.of(execution));

        assertThatThrownBy(() -> loader.load(executionRef()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ERROR_MISMATCHED_TOOL_PROCESS);
    }

    @Test
    void rejectsNonterminalToolProcessBeforeItCanCreateADanglingProtocolCall() {
        stubCurrentExecution(SESSION_ID);
        ToolThinkingProcessItem prepared = preparedToolItem();
        ConversationMessage assistant = assistantMessage(List.of(prepared), FINAL_ANSWER);
        when(messages.findByConversationSessionIdUpTo(SESSION_ID, INPUT_BOUNDARY))
                .thenReturn(List.of(userMessage(), assistant));
        when(toolExecutions.findByExecutionIds(List.of(PREVIOUS_EXECUTION_ID)))
                .thenReturn(
                        List.of(
                                toolExecution(
                                        FIRST_TOOL_EXECUTION_ID,
                                        FIRST_TOOL_CALL_ID,
                                        FIRST_TOOL_NAME,
                                        1L,
                                        firstProcessId())));

        assertThatThrownBy(() -> loader.load(executionRef()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ERROR_MISMATCHED_TOOL_PROCESS);
    }

    @Test
    void rejectsAnExecutionFromAnotherSessionBeforeReadingHistory() {
        stubCurrentExecution(OTHER_SESSION_ID);

        assertThatThrownBy(() -> loader.load(executionRef()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ERROR_EXECUTION_SESSION_MISMATCH);
    }

    private void stubCurrentExecution(String sessionId) {
        AgentExecution execution = new AgentExecution();
        execution.setExecutionId(EXECUTION_ID);
        execution.setConversationSessionId(sessionId);
        when(agentExecutions.findByExecutionId(EXECUTION_ID)).thenReturn(execution);
    }

    private static ContextExecutionRef executionRef() {
        return new ContextExecutionRef(
                TENANT_ID, SESSION_ID, EXECUTION_ID, OWNER_ID, 1L, INPUT_BOUNDARY);
    }

    private static ConversationMessage userMessage() {
        ConversationMessage message = new ConversationMessage();
        message.setConversationMessageId(USER_MESSAGE_ID);
        message.setConversationSessionId(SESSION_ID);
        message.setExecutionId(EXECUTION_ID);
        message.setConversationMessageSequence(10L);
        message.setRole(ROLE_USER);
        message.setContent(QUESTION);
        return message;
    }

    private static ConversationMessage assistantWithCompleteProcess() {
        List<ThinkingProcessItem> process =
                List.of(
                        thinkingItem(1L, 1L, FIRST_THINKING),
                        toolItem(
                                firstProcessId(),
                                2L,
                                FIRST_TOOL_EXECUTION_ID,
                                FIRST_TOOL_CALL_ID,
                                FIRST_TOOL_NAME,
                                completePayload(Map.of(ARGUMENT_KEY, ARGUMENT_VALUE)),
                                completePayload(Map.of(RESULT_KEY, FIRST_RESULT))),
                        thinkingItem(3L, 2L, SECOND_THINKING),
                        toolItem(
                                secondProcessId(),
                                4L,
                                SECOND_TOOL_EXECUTION_ID,
                                SECOND_TOOL_CALL_ID,
                                SECOND_TOOL_NAME,
                                completePayload(Map.of(ARGUMENT_KEY, ARGUMENT_VALUE)),
                                completePayload(Map.of(RESULT_KEY, SECOND_RESULT))),
                        thinkingItem(5L, 3L, FINAL_THINKING));
        return assistantMessage(process, FINAL_ANSWER);
    }

    private static ConversationMessage assistantMessage(
            List<? extends ThinkingProcessItem> process, String content) {
        ConversationMessage message = new ConversationMessage();
        message.setConversationMessageId(ASSISTANT_MESSAGE_ID);
        message.setConversationSessionId(SESSION_ID);
        message.setExecutionId(PREVIOUS_EXECUTION_ID);
        message.setConversationMessageSequence(INPUT_BOUNDARY);
        message.setRole(ROLE_ASSISTANT);
        message.setContent(content);
        message.setThinkingProcess(List.copyOf(process));
        return message;
    }

    private static ModelThinkingProcessItem thinkingItem(
            long processSequence, long invocationSequence, String content) {
        Instant completedAt = CREATED_AT.plusSeconds(processSequence);
        return new ModelThinkingProcessItem(
                new UUID(0L, processSequence),
                processSequence,
                ThinkingProcessStatus.SUCCEEDED,
                CREATED_AT,
                CREATED_AT,
                completedAt,
                completedAt.toEpochMilli() - CREATED_AT.toEpochMilli(),
                ThinkingProcessItem.CURRENT_SCHEMA_VERSION,
                content,
                false,
                content.length(),
                PAYLOAD_SHA256,
                invocationSequence,
                MODEL_PROVIDER,
                MODEL_NAME);
    }

    private static ToolThinkingProcessItem toolItem(
            UUID processItemId,
            long processSequence,
            String toolExecutionId,
            String toolCallId,
            String toolName,
            BoundedProcessPayload input,
            BoundedProcessPayload output) {
        Instant completedAt = CREATED_AT.plusSeconds(processSequence);
        long invocationSequence = toolName.equals(FIRST_TOOL_NAME) ? 1L : 2L;
        return new ToolThinkingProcessItem(
                processItemId,
                processSequence,
                ThinkingProcessStatus.SUCCEEDED,
                CREATED_AT,
                CREATED_AT,
                completedAt,
                completedAt.toEpochMilli() - CREATED_AT.toEpochMilli(),
                ThinkingProcessItem.CURRENT_SCHEMA_VERSION,
                toolName,
                toolName,
                toolExecutionId,
                toolCallId,
                invocationSequence,
                input,
                output,
                true,
                null,
                null);
    }

    private static ToolThinkingProcessItem preparedToolItem() {
        return new ToolThinkingProcessItem(
                firstProcessId(),
                1L,
                ThinkingProcessStatus.PREPARED,
                CREATED_AT,
                null,
                null,
                null,
                ThinkingProcessItem.CURRENT_SCHEMA_VERSION,
                FIRST_TOOL_NAME,
                FIRST_TOOL_NAME,
                FIRST_TOOL_EXECUTION_ID,
                FIRST_TOOL_CALL_ID,
                1L,
                completePayload(Map.of(ARGUMENT_KEY, ARGUMENT_VALUE)),
                null,
                null,
                null,
                null);
    }

    private static BoundedProcessPayload completePayload(Object value) {
        return new BoundedProcessPayload(value, null, false, 16L, PAYLOAD_SHA256, CONTENT_TYPE);
    }

    private static BoundedProcessPayload truncatedPayload(String preview) {
        return new BoundedProcessPayload(null, preview, true, 1_024L, PAYLOAD_SHA256, CONTENT_TYPE);
    }

    private static ToolExecutionEntity toolExecution(
            String toolExecutionId,
            String toolCallId,
            String toolName,
            long invocationSequence,
            UUID processItemId) {
        ToolExecutionEntity execution = new ToolExecutionEntity();
        execution.setToolExecutionId(toolExecutionId);
        execution.setExecutionId(PREVIOUS_EXECUTION_ID);
        execution.setAssistantConversationMessageId(ASSISTANT_MESSAGE_ID);
        execution.setToolProcessItemId(processItemId);
        execution.setToolCallId(toolCallId);
        execution.setToolName(toolName);
        execution.setModelInvocationSequence(invocationSequence);
        execution.setStatus(TOOL_STATUS_SUCCEEDED);
        return execution;
    }

    private static UUID firstProcessId() {
        return UUID.fromString(FIRST_PROCESS_UUID);
    }

    private static UUID secondProcessId() {
        return UUID.fromString(SECOND_PROCESS_UUID);
    }

    private enum ToolMismatch {
        TOOL_EXECUTION_ID {
            @Override
            void apply(ToolExecutionEntity execution) {
                execution.setToolExecutionId(WRONG_TOOL_EXECUTION_ID);
            }
        },
        TOOL_CALL_ID {
            @Override
            void apply(ToolExecutionEntity execution) {
                execution.setToolCallId(WRONG_TOOL_CALL_ID);
            }
        },
        TOOL_NAME {
            @Override
            void apply(ToolExecutionEntity execution) {
                execution.setToolName(WRONG_TOOL_NAME);
            }
        },
        INVOCATION_SEQUENCE {
            @Override
            void apply(ToolExecutionEntity execution) {
                execution.setModelInvocationSequence(99L);
            }
        },
        PARENT_EXECUTION_ID {
            @Override
            void apply(ToolExecutionEntity execution) {
                execution.setExecutionId(EXECUTION_ID);
            }
        };

        abstract void apply(ToolExecutionEntity execution);
    }
}
