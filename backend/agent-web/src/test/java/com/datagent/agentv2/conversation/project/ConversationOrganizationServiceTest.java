package com.datagent.agentv2.conversation.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.conversation.domain.ConversationProject;
import com.datagent.agentv2.conversation.domain.ConversationSession;
import com.datagent.agentv2.persistence.service.ConversationProjectService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ConversationOrganizationServiceTest {
    private final com.datagent.agentv2.persistence.service.development
                    .DevelopmentContextPersistenceService
            development =
                    mock(
                            com.datagent.agentv2.persistence.service.development
                                    .DevelopmentContextPersistenceService.class);
    private final ConversationProjectService projects = mock(ConversationProjectService.class);
    private final ConversationSessionService sessions = mock(ConversationSessionService.class);
    private final ConversationOrganizationService service =
            new ConversationOrganizationService(projects, sessions, development);

    @Test
    void movingPinnedSessionLocksDestinationFirstAndPreservesPinTime() {
        var at = Instant.parse("2026-09-15T01:00:00Z");
        var session =
                ConversationSession.builder()
                        .conversationSessionId("s1")
                        .title("test")
                        .lastActivityAt(at)
                        .pinnedAt(at)
                        .build();
        when(projects.lockByProjectId("p1"))
                .thenReturn(ConversationProject.create("p1", "Project"));
        when(sessions.lockById("s1")).thenReturn(session);
        when(sessions.updateOrganization("s1", "p1", at, 0)).thenReturn(true);
        when(sessions.findByUuid("s1")).thenReturn(session);
        assertThat(service.setOrganization("s1", "p1", true, 0)).isSameAs(session);
        var order = inOrder(projects, sessions, development);
        order.verify(projects).lockByProjectId("p1");
        order.verify(sessions).lockById("s1");
        order.verify(sessions).updateOrganization("s1", "p1", at, 0);
    }

    @Test
    void noOpDoesNotWriteButStaleVersionIsRejected() {
        var session = ConversationSession.create("s1", "test", null, Instant.now());
        when(sessions.lockById("s1")).thenReturn(session);
        assertThat(service.setOrganization("s1", null, false, 0)).isSameAs(session);
        verify(sessions, never()).updateOrganization(anyString(), any(), any(), anyLong());
        assertThatThrownBy(() -> service.setOrganization("s1", null, false, 1))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        verifyNoInteractions(projects);
    }

    @Test
    void boundSessionCannotMoveToAnotherProject() {
        var session = ConversationSession.create("s1", "test", null, Instant.now());
        var binding =
                new com.datagent.agentv2.persistence.entity.development.DevelopmentSessionEntity();
        binding.setProjectId("original");
        when(development.session("s1")).thenReturn(binding);
        when(sessions.lockById("s1")).thenReturn(session);
        when(projects.lockByProjectId("another"))
                .thenReturn(ConversationProject.create("another", "Another"));
        assertThatThrownBy(() -> service.setOrganization("s1", "another", false, 0))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        verify(sessions, never()).updateOrganization(anyString(), any(), any(), anyLong());
    }

    @Test
    void missingDestinationAndBlankIdCannotChangeSession() {
        assertThatThrownBy(() -> service.setOrganization("s1", "missing", false, 0))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> service.setOrganization("s1", " ", false, 0))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(sessions);
    }
}
