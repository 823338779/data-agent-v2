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
    private final ConversationProjectService projects = mock(ConversationProjectService.class);
    private final ConversationSessionService sessions = mock(ConversationSessionService.class);
    private final ConversationOrganizationService service =
            new ConversationOrganizationService(projects, sessions);

    @Test
    void movingPinnedSessionLocksDestinationFirstAndPreservesPinTime() {
        var at = Instant.parse("2026-09-15T01:00:00Z");
        var session = ConversationSession.create("s1", "test", null, at);
        session.setPinnedAt(at);
        when(projects.lockByProjectId("p1"))
                .thenReturn(ConversationProject.create("p1", "Project"));
        when(sessions.lockById("s1")).thenReturn(session);
        when(sessions.updateOrganization("s1", "p1", at, 0)).thenReturn(true);
        when(sessions.findByUuid("s1")).thenReturn(session);
        assertThat(service.setOrganization("s1", "p1", true, 0)).isSameAs(session);
        var order = inOrder(projects, sessions);
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
