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
    void groupsRetryAndToolProtocolIntoOneTurn() {
        List<ConversationTurn> turns = grouper.group(messagesWithFailedRunAndRetry());

        assertThat(turns).hasSize(1);
        assertThat(turns.getFirst().messages())
                .extracting(ContextMessage::role)
                .containsExactly(USER, ASSISTANT, ASSISTANT, TOOL, ASSISTANT);
        assertThat(turns.getFirst().messages().get(1).thinking()).isEqualTo(THINKING);
    }

    @Test
    void separatesTurnsAndPreservesSourceSequenceRange() {
        List<ConversationTurn> turns =
                grouper.groupSources(
                        List.of(
                                ConversationTurnGrouper.SourceMessage.user(
                                        11L, ContextMessage.user(QUESTION)),
                                ConversationTurnGrouper.SourceMessage.assistant(
                                        15L, List.of(ContextMessage.assistant(FINAL_ANSWER, null))),
                                ConversationTurnGrouper.SourceMessage.user(
                                        21L, ContextMessage.user(NEXT_QUESTION)),
                                ConversationTurnGrouper.SourceMessage.assistant(22L, List.of())));

        assertThat(turns).hasSize(2);
        assertThat(turns.getFirst())
                .extracting(
                        ConversationTurn::ordinal,
                        ConversationTurn::startMessageSequence,
                        ConversationTurn::endMessageSequence)
                .containsExactly(1L, 11L, 15L);
        assertThat(turns.get(1))
                .extracting(
                        ConversationTurn::ordinal,
                        ConversationTurn::startMessageSequence,
                        ConversationTurn::endMessageSequence)
                .containsExactly(2L, 21L, 22L);
        assertThat(turns.get(1).messages()).extracting(ContextMessage::role).containsExactly(USER);
    }

    @Test
    void everyUserStartsANewTurnEvenWhenOlderAssistantsAreEmpty() {
        List<ConversationTurn> turns =
                grouper.groupSources(
                        List.of(
                                ConversationTurnGrouper.SourceMessage.user(
                                        1L, ContextMessage.user("cancelled question")),
                                ConversationTurnGrouper.SourceMessage.assistant(2L, List.of()),
                                ConversationTurnGrouper.SourceMessage.user(
                                        3L, ContextMessage.user("failed question")),
                                ConversationTurnGrouper.SourceMessage.assistant(4L, List.of()),
                                ConversationTurnGrouper.SourceMessage.user(
                                        5L, ContextMessage.user("latest question")),
                                ConversationTurnGrouper.SourceMessage.assistant(6L, List.of())));

        assertThat(turns).hasSize(3);
        assertThat(turns).extracting(ConversationTurn::ordinal).containsExactly(1L, 2L, 3L);
        assertThat(turns)
                .extracting(turn -> turn.messages().getFirst().content())
                .containsExactly("cancelled question", "failed question", "latest question");
        assertThat(turns.getLast().endMessageSequence()).isEqualTo(6L);
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
