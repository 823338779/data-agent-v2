package com.datagent.agentv2.conversation.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.AgentExecutionStreamCheckpoint;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.ConversationSession;
import com.datagent.agentv2.conversation.domain.MessageRole;
import com.datagent.agentv2.conversation.enums.ExecutionPhase;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import com.datagent.agentv2.conversation.enums.SessionExecutionStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationViewCompositionTest {

    @Test
    void targetTypesOwnDefaultsAndMultiSourceComposition() {
        ConversationSession session = new ConversationSession();
        session.setConversationSessionId("session-1");
        session.setTitle("title");
        session.setTag(null);
        session.setVersion(2L);
        AgentExecution execution = new AgentExecution();
        execution.setExecutionId("execution-1");
        execution.setConversationSessionId("session-1");
        execution.setStatus(ExecutionStatus.RUNNING.value());
        ConversationMessage message = new ConversationMessage();
        message.setConversationMessageId("message-1");
        message.setRole(MessageRole.USER);
        message.setContent("question");
        message.setVersion(null);
        message.setThinkingProcess(List.of());
        AgentExecutionStreamCheckpoint checkpoint = new AgentExecutionStreamCheckpoint();
        checkpoint.setModelInvocationSequence(2L);
        checkpoint.setPhase(ExecutionPhase.ANSWERING.value());
        checkpoint.setOutputDeltaSequence(3L);
        checkpoint.setAnswer("answer");

        ConversationSummaryView summary =
                ConversationSummaryView.composeFromSession(session, execution);
        ConversationView.MessageView messageView =
                ConversationView.MessageView.composeFromMessage(message, null);
        ConversationView.AgentExecutionView executionView =
                ConversationView.AgentExecutionView.composeFromExecution(
                        execution, "question", List.of(), checkpoint);

        assertThat(summary.tag()).isEmpty();
        assertThat(summary.status()).isEqualTo(SessionExecutionStatus.RUNNING);
        assertThat(messageView.version()).isZero();
        assertThat(executionView.progress()).isEqualTo(70);
        assertThat(executionView.streamCheckpoint().answer()).isEqualTo("answer");
    }
}
