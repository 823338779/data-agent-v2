package com.datagent.agentv2.conversation.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.artifact.application.ArtifactProjection;
import com.datagent.agentv2.artifact.application.MessageArtifactBundle;
import com.datagent.agentv2.artifact.application.MessageUiBlockProjection;
import com.datagent.agentv2.artifact.domain.ArtifactType;
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
        execution.setStatus(ExecutionStatus.RUNNING);
        ConversationMessage message = new ConversationMessage();
        message.setConversationMessageId("message-1");
        message.setRole(MessageRole.USER);
        message.setContent("question");
        message.setVersion(null);
        message.setThinkingProcess(List.of());
        AgentExecutionStreamCheckpoint checkpoint = new AgentExecutionStreamCheckpoint();
        checkpoint.setModelInvocationSequence(2L);
        checkpoint.setPhase(ExecutionPhase.ANSWERING);
        checkpoint.setOutputDeltaSequence(3L);
        checkpoint.setAnswer("answer");

        ConversationSummaryView summary =
                ConversationSummaryView.composeFromSession(session, execution);
        MessageArtifactBundle artifactBundle =
                new MessageArtifactBundle(
                        List.of(
                                new ArtifactProjection(
                                        "artifact-1",
                                        ArtifactType.DOCX,
                                        "report.docx",
                                        ArtifactType.DOCX.mediaType(),
                                        3,
                                        "a".repeat(64),
                                        1,
                                        "/api/v1/agent-sessions/session-1/artifacts/artifact-1/content")),
                        List.of(
                                new MessageUiBlockProjection(
                                        "block-1", "artifact-card", 1, "default", null, 0)));
        ConversationView.MessageView messageView =
                ConversationView.MessageView.composeFromMessage(message, null, artifactBundle);
        ConversationView.AgentExecutionView executionView =
                ConversationView.AgentExecutionView.composeFromExecution(
                        execution, "question", List.of(), checkpoint);

        assertThat(summary.tag()).isEmpty();
        assertThat(summary.status()).isEqualTo(SessionExecutionStatus.RUNNING);
        assertThat(messageView.version()).isZero();
        assertThat(messageView.artifacts()).extracting(ArtifactProjection::artifactId)
                .containsExactly("artifact-1");
        assertThat(messageView.uiBlocks()).extracting(MessageUiBlockProjection::blockId)
                .containsExactly("block-1");
        assertThat(executionView.progress()).isEqualTo(70);
        assertThat(executionView.streamCheckpoint().answer()).isEqualTo("answer");
    }
}
