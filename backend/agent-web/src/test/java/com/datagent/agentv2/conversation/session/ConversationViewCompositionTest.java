package com.datagent.agentv2.conversation.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.AgentExecutionStreamCheckpoint;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.ConversationSession;
import com.datagent.agentv2.conversation.domain.MessageRole;
import com.datagent.agentv2.conversation.enums.ExecutionPhase;
import com.datagent.agentv2.conversation.enums.SessionExecutionStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationViewCompositionTest {

    @Test
    void targetTypesOwnDefaultsAndMultiSourceComposition() {
        ConversationSession session =
                ConversationSession.builder()
                        .conversationSessionId("session-1")
                        .title("title")
                        .lastActivityAt(java.time.Instant.now())
                        .version(2L)
                        .build();
        AgentExecution execution =
                AgentExecution.running(
                        "execution-1",
                        "session-1",
                        "message-1",
                        "key",
                        "hash",
                        java.time.Instant.now().plusSeconds(60));
        ConversationMessage message =
                ConversationMessage.builder()
                        .conversationMessageId("message-1")
                        .conversationSessionId("session-1")
                        .role(MessageRole.USER)
                        .content("question")
                        .version(null)
                        .thinkingProcess(List.of())
                        .build();
        AgentExecutionStreamCheckpoint checkpoint =
                AgentExecutionStreamCheckpoint.builder()
                        .executionId("execution-1")
                        .modelInvocationSequence(2L)
                        .phase(ExecutionPhase.ANSWERING)
                        .outputDeltaSequence(3L)
                        .answer("answer")
                        .build();

        ConversationSummaryView summary =
                ConversationSummaryView.composeFromSession(session, execution);
        List<MessageUiBlockProjection> uiBlocks =
                List.of(
                        new MessageUiBlockProjection(
                                "block-1", "git-change-card", 1, "default", null, 0));
        ConversationView.MessageView messageView =
                ConversationView.MessageView.composeFromMessage(message, null, uiBlocks);
        ConversationView.AgentExecutionView executionView =
                ConversationView.AgentExecutionView.composeFromExecution(
                        execution, "question", List.of(), checkpoint);

        assertThat(summary.tag()).isEmpty();
        assertThat(summary.status()).isEqualTo(SessionExecutionStatus.RUNNING);
        assertThat(messageView.version()).isZero();
        assertThat(messageView.uiBlocks())
                .extracting(MessageUiBlockProjection::blockId)
                .containsExactly("block-1");
        assertThat(ConversationView.MessageView.composeFromMessage(message, null).uiBlocks())
                .isEmpty();
        assertThat(executionView.progress()).isEqualTo(70);
        assertThat(executionView.streamCheckpoint().answer()).isEqualTo("answer");
    }
}
