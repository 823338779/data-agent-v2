package com.datagent.agentv2.context.hierarchical.planning;

import static com.datagent.agentv2.conversation.context.ContextMessage.Role.ASSISTANT;
import static com.datagent.agentv2.conversation.context.ContextMessage.Role.TOOL;
import static com.datagent.agentv2.conversation.context.ContextMessage.Role.USER;
import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.context.hierarchical.domain.ConversationTurn;
import com.datagent.agentv2.conversation.context.ContextMessage;
import com.datagent.agentv2.conversation.context.ContextToolCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConversationTurnGrouperTest {

    private static final String QUESTION = "Find the weather";
    private static final String NEXT_QUESTION = "And tomorrow?";
    private static final String PARTIAL_ANSWER = "Checking the forecast";
    private static final String FINAL_ANSWER = "It is sunny today.";
    private static final String THINKING = "Need a weather lookup";
    private static final String TOOL_CALL_ID = "call-weather";
    private static final String TOOL_NAME = "weather";
    private static final String TOOL_ARGUMENT_KEY = "day";
    private static final String TOOL_ARGUMENT = "today";
    private static final String TOOL_RESULT = "sunny";

    private final ConversationTurnGrouper grouper = new ConversationTurnGrouper();

    @Test
    void groupsRetryAndToolProtocolIntoOneCompletedTurn() {
        List<ConversationTurn> turns = grouper.group(messagesWithFailedRunAndRetry());

        assertThat(turns).hasSize(1);
        assertThat(turns.getFirst().completed()).isTrue();
        assertThat(turns.getFirst().messages())
                .extracting(ContextMessage::role)
                .containsExactly(USER, ASSISTANT, ASSISTANT, TOOL, ASSISTANT);
        assertThat(turns.getFirst().messages().get(1).thinking()).isEqualTo(THINKING);
    }

    @Test
    void separatesTheCurrentIncompleteTurnAndPreservesSourceSequenceRange() {
        List<ConversationTurn> turns =
                grouper.groupSources(
                        List.of(
                                ConversationTurnGrouper.SourceMessage.user(
                                        11L, ContextMessage.user(QUESTION)),
                                ConversationTurnGrouper.SourceMessage.assistant(
                                        15L,
                                        true,
                                        List.of(ContextMessage.assistant(FINAL_ANSWER, null))),
                                ConversationTurnGrouper.SourceMessage.user(
                                        21L, ContextMessage.user(NEXT_QUESTION)),
                                ConversationTurnGrouper.SourceMessage.assistant(
                                        22L, false, List.of())));

        assertThat(turns).hasSize(2);
        assertThat(turns.getFirst())
                .extracting(
                        ConversationTurn::ordinal,
                        ConversationTurn::startMessageSequence,
                        ConversationTurn::endMessageSequence,
                        ConversationTurn::completed)
                .containsExactly(1L, 11L, 15L, true);
        assertThat(turns.get(1))
                .extracting(
                        ConversationTurn::ordinal,
                        ConversationTurn::startMessageSequence,
                        ConversationTurn::endMessageSequence,
                        ConversationTurn::completed)
                .containsExactly(2L, 21L, 22L, false);
        assertThat(turns.get(1).messages()).extracting(ContextMessage::role).containsExactly(USER);
    }

    private static List<ContextMessage> messagesWithFailedRunAndRetry() {
        ContextToolCall toolCall =
                new ContextToolCall(
                        TOOL_CALL_ID, TOOL_NAME, Map.of(TOOL_ARGUMENT_KEY, TOOL_ARGUMENT));

        // The partial assistant is the failed run; no new user message precedes the retry protocol.
        return List.of(
                ContextMessage.user(QUESTION),
                ContextMessage.assistant(PARTIAL_ANSWER, THINKING),
                ContextMessage.assistantToolCalls(List.of(toolCall)),
                ContextMessage.tool(TOOL_RESULT, TOOL_CALL_ID, TOOL_NAME),
                ContextMessage.assistant(FINAL_ANSWER, null));
    }
}
