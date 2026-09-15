package com.datagent.agentv2.conversation.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.conversation.domain.ConversationProject;
import com.datagent.agentv2.persistence.service.ConversationProjectService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.platform.id.IdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ConversationProjectApplicationServiceTest {
    private final ConversationProjectService projects = mock(ConversationProjectService.class);
    private final ConversationSessionService sessions = mock(ConversationSessionService.class);
    private final IdGenerator ids = mock(IdGenerator.class);
    private final ConversationProjectApplicationService service =
            new ConversationProjectApplicationService(projects, sessions, ids);

    @Test
    void deletionValidatesVersionBeforeDetachingSessions() {
        when(projects.lockByProjectId("p1"))
                .thenReturn(ConversationProject.create("p1", "Project"));
        assertThatThrownBy(() -> service.delete("p1", 1))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        verifyNoInteractions(sessions);
        when(projects.deleteProject("p1", 0)).thenReturn(true);
        service.delete("p1", 0);
        var order = inOrder(sessions, projects);
        order.verify(sessions).detachProject("p1");
        order.verify(projects).deleteProject("p1", 0);
    }

    @Test
    void invalidNameIsAClientErrorAndCannotBePersisted() {
        when(ids.next("project")).thenReturn("p1");
        assertThatThrownBy(() -> service.create(" "))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(projects, sessions);
    }

    @Test
    void nameLengthCountsUtf16UnitsAfterTrimming() {
        assertThat(ConversationProject.create("p1", "  " + "中".repeat(60) + "  ").name())
                .hasSize(60);
        assertThat(ConversationProject.create("p1", "😀".repeat(30)).name()).hasSize(60);
        assertThatThrownBy(() -> service.create("中".repeat(61)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(() -> service.create("😀".repeat(31)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(projects, sessions);
    }
}
