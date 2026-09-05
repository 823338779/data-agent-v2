package com.datagent.agentv2.conversation.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.artifact.application.ArtifactQueryService;
import com.datagent.agentv2.conversation.domain.ConversationSession;
import com.datagent.agentv2.conversation.execution.AgentExecutionSubmissionService;
import com.datagent.agentv2.persistence.service.AgentExecutionEventService;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.AgentExecutionStreamCheckpointService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ConversationApplicationServiceTest {

    @Test
    void createsEmptyViewWithoutQueryingMessagesOrExecutions() {
        ConversationSessionService sessions = mock(ConversationSessionService.class);
        ConversationMessageService messages = mock(ConversationMessageService.class);
        AgentExecutionService executions = mock(AgentExecutionService.class);
        AgentExecutionEventService events = mock(AgentExecutionEventService.class);
        AgentExecutionStreamCheckpointService checkpoints =
                mock(AgentExecutionStreamCheckpointService.class);
        AgentExecutionSubmissionService submissions = mock(AgentExecutionSubmissionService.class);
        IdGenerator ids = mock(IdGenerator.class);
        ArtifactQueryService artifacts = mock(ArtifactQueryService.class);
        LocalDateTime createdAt = LocalDateTime.parse("2026-08-22T10:00:00");
        LocalDateTime updatedAt = LocalDateTime.parse("2026-08-22T10:00:01");
        when(ids.next("conversation_session")).thenReturn("conversation-session-1");
        doAnswer(
                        invocation -> {
                            ConversationSession session = invocation.getArgument(0);
                            session.setCreatedAt(createdAt);
                            session.setUpdatedAt(updatedAt);
                            return true;
                        })
                .when(sessions)
                .save(any(ConversationSession.class));
        ConversationApplicationService service =
                new ConversationApplicationService(
                        sessions,
                        messages,
                        executions,
                        events,
                        checkpoints,
                        submissions,
                        ids,
                        artifacts);

        ConversationView view = service.create("Test conversation", null);

        assertThat(view.conversationSessionId()).isEqualTo("conversation-session-1");
        assertThat(view.title()).isEqualTo("Test conversation");
        assertThat(view.tag()).isEmpty();
        assertThat(view.createdAt()).isEqualTo(createdAt);
        assertThat(view.updatedAt()).isEqualTo(updatedAt);
        assertThat(view.version()).isZero();
        assertThat(view.messages()).isEmpty();
        assertThat(view.execution()).isNull();
        verifyNoInteractions(messages, executions, events, checkpoints, submissions, artifacts);
    }
}
