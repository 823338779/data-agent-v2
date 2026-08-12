package com.datagent.agentv2.context.lsm.planning;

import static com.datagent.agentv2.conversation.context.ContextMessage.Role.ASSISTANT;
import static com.datagent.agentv2.conversation.context.ContextMessage.Role.TOOL;
import static com.datagent.agentv2.conversation.context.ContextMessage.Role.USER;
import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.context.lsm.domain.ConversationTurn;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextToolCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConversationTurnGrouperTest {

    private static final String FIRST_QUESTION = "Find the weather";
    private static final String CURRENT_QUESTION = "And tomorrow?";
    private static final String TOOL_CALL_ID = "call-weather";
    private static final String TOOL_NAME = "weather";
    private static final String TOOL_RESULT = "sunny";
    private static final String PARTIAL_ANSWER = "Checking the forecast";
    private static final String FINAL_ANSWER = "It is sunny today.";
    private static final String RETRY_ARGUMENT = "today";
    private static final String EMPTY_ASSISTANT_CONTENT = "";

    private final ConversationTurnGrouper grouper = new ConversationTurnGrouper();

    @Test
    void groupsRetryProcessesIntoOneTurnAndLeavesCurrentTurnUnfinished() {
        List<ConversationTurn> turns = grouper.group(historyWithRetryAndCurrentQuestion());

        assertThat(turns).hasSize(2);
        assertThat(turns.getFirst().completed()).isTrue();
        assertThat(turns.getFirst().messages())
                .extracting(ContextMessage::role)
                .containsExactly(USER, ASSISTANT, ASSISTANT, TOOL, ASSISTANT);
        assertThat(turns.get(1).completed()).isFalse();
    }

    @Test
    void keepsToolCallsAndTheirObservationsInTheSameCompletedTurn() {
        List<ConversationTurn> turns = grouper.group(historyWithRetryAndCurrentQuestion());

        assertThat(turns.getFirst().messages())
                .extracting(ContextMessage::toolCallId)
                .containsExactly(null, null, null, TOOL_CALL_ID, null);
    }

    @Test
    void keepsTurnUnfinishedWhenPartialAttemptIsFollowedByRetryPlaceholder() {
        List<ConversationTurn> turns =
                grouper.group(
                        List.of(
                                ContextMessage.user(FIRST_QUESTION),
                                ContextMessage.assistant(PARTIAL_ANSWER, null),
                                ContextMessage.assistant(EMPTY_ASSISTANT_CONTENT, null)));

        assertThat(turns).singleElement().extracting(ConversationTurn::completed).isEqualTo(false);
    }

    private static List<ContextMessage> historyWithRetryAndCurrentQuestion() {
        ContextToolCall call =
                new ContextToolCall(TOOL_CALL_ID, TOOL_NAME, Map.of(TOOL_NAME, RETRY_ARGUMENT));
        return List.of(
                ContextMessage.user(FIRST_QUESTION),
                ContextMessage.assistant(PARTIAL_ANSWER, null),
                ContextMessage.assistantToolCalls(List.of(call)),
                ContextMessage.tool(TOOL_RESULT, TOOL_CALL_ID, TOOL_NAME),
                ContextMessage.assistant(FINAL_ANSWER, null),
                ContextMessage.user(CURRENT_QUESTION));
    }
}
