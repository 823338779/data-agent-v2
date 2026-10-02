package com.datagent.agentv2.conversation.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.ConversationSession;
import com.datagent.agentv2.conversation.domain.MessageUiBlock;
import com.datagent.agentv2.conversation.execution.AgentExecutionSubmissionService;
import com.datagent.agentv2.persistence.service.AgentExecutionEventService;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.AgentExecutionStreamCheckpointService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.persistence.service.MessageUiBlockService;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConversationApplicationServiceTest {

    @Test
    void snapshotLoadsRegisteredCardsOnceForAllMessages() {
        var sessions = mock(ConversationSessionService.class);
        var messages = mock(ConversationMessageService.class);
        var executions = mock(AgentExecutionService.class);
        var blocks = mock(MessageUiBlockService.class);
        var session =
                ConversationSession.create("session-1", "title", null, java.time.Instant.now());
        var first = ConversationMessage.assistantPlaceholder("message-1", "session-1", "run-1", 1);
        var second = ConversationMessage.assistantPlaceholder("message-2", "session-1", "run-2", 2);
        when(sessions.findByUuid("session-1")).thenReturn(session);
        when(messages.findByConversationSessionId("session-1")).thenReturn(List.of(first, second));
        var payload = Map.of("changeId", "change-1", "sessionId", "session-1");
        when(blocks.findByMessageIds(List.of("message-1", "message-2")))
                .thenReturn(
                        List.of(
                                new MessageUiBlock(
                                        "block-1",
                                        "message-1",
                                        "git-change-card",
                                        1,
                                        "default",
                                        payload,
                                        0),
                                new MessageUiBlock(
                                        "legacy-block",
                                        "message-1",
                                        "artifact-card",
                                        1,
                                        "default",
                                        Map.of("artifactId", "legacy"),
                                        1)));
        var service =
                new ConversationApplicationService(
                        sessions,
                        messages,
                        executions,
                        mock(AgentExecutionEventService.class),
                        mock(AgentExecutionStreamCheckpointService.class),
                        mock(AgentExecutionSubmissionService.class),
                        mock(IdGenerator.class),
                        blocks,
                        mock(
                                com.datagent.agentv2.persistence.service.ConversationProjectService
                                        .class));

        var view = service.get("session-1");

        assertThat(view.messages()).hasSize(2);
        assertThat(view.messages().getFirst().uiBlocks())
                .singleElement()
                .satisfies(
                        block -> {
                            assertThat(block.blockId()).isEqualTo("block-1");
                            assertThat(block.payload()).isEqualTo(payload);
                        });
        assertThat(view.messages().getLast().uiBlocks()).isEmpty();
        verify(blocks).findByMessageIds(List.of("message-1", "message-2"));
        verifyNoMoreInteractions(blocks);
    }

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
        MessageUiBlockService uiBlocks = mock(MessageUiBlockService.class);
        LocalDateTime createdAt = LocalDateTime.parse("2026-08-22T10:00:00");
        LocalDateTime updatedAt = LocalDateTime.parse("2026-08-22T10:00:01");
        when(ids.next("conversation_session")).thenReturn("conversation-session-1");
        doAnswer(
                        invocation -> {
                            ConversationSession session = invocation.getArgument(0);
                            session.acknowledgePersistence(
                                    session.getVersion(), createdAt, updatedAt);
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
                        uiBlocks,
                        mock(
                                com.datagent.agentv2.persistence.service.ConversationProjectService
                                        .class));

        ConversationView view = service.create("Test conversation", null);

        assertThat(view.conversationSessionId()).isEqualTo("conversation-session-1");
        assertThat(view.title()).isEqualTo("Test conversation");
        assertThat(view.tag()).isEmpty();
        assertThat(view.createdAt()).isEqualTo(createdAt);
        assertThat(view.updatedAt()).isEqualTo(updatedAt);
        assertThat(view.version()).isZero();
        assertThat(view.messages()).isEmpty();
        assertThat(view.execution()).isNull();
        verifyNoInteractions(messages, executions, events, checkpoints, submissions, uiBlocks);
    }
}
