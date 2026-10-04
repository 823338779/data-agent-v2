package com.datagent.agentv2.git;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.datagent.agentv2.conversation.domain.ConversationSession;
import com.datagent.agentv2.git.application.GitWorkspaceService;
import com.datagent.agentv2.git.model.GitWorkspaceView;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GitWorkspaceControllerTest {
    @Test
    void recoversExplicitWorkspaceWithoutExecutionBinding() throws Exception {
        var workspaces = mock(GitWorkspaceService.class);
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new GitWorkspaceController(
                                        workspaces, mock(ConversationSessionService.class)))
                        .build();
        mvc.perform(
                        post("/api/v1/git-workspaces/w1/restart")
                                .contentType("application/json")
                                .content("{\"expectedRevision\":4}"))
                .andExpect(status().isOk());
        verify(workspaces).prepare("w1", true, 4L);
    }

    @Test
    void createsAndAssociatesExplicitWorkspaceWithoutRemoteOrPathInput() throws Exception {
        var workspaces = mock(GitWorkspaceService.class);
        var sessions = mock(ConversationSessionService.class);
        when(sessions.findByUuid("s1")).thenReturn(mock(ConversationSession.class));
        String sha = "a".repeat(40);
        when(workspaces.create("s1", "b1", sha, "key"))
                .thenReturn(
                        new GitWorkspaceView(
                                "w1", sha, 0, "READY", null, "project", "b1", "refs/heads/dev"));
        var mvc =
                MockMvcBuilders.standaloneSetup(new GitWorkspaceController(workspaces, sessions))
                        .build();
        String body =
                "{\"sessionId\":\"s1\",\"branchId\":\"b1\",\"baseCommitSha\":\""
                        + sha
                        + "\",\"idempotencyKey\":\"key\"}";
        mvc.perform(post("/api/v1/git-workspaces").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value("w1"))
                .andExpect(jsonPath("$.branchId").value("b1"));
        mvc.perform(put("/api/v1/agent-sessions/s1/git-workspaces/w1")).andExpect(status().isOk());
        verify(workspaces).attach("s1", "w1");
        mvc.perform(
                        post("/api/v1/git-workspaces")
                                .contentType("application/json")
                                .content(
                                        body.substring(0, body.length() - 1)
                                                + ",\"remoteUri\":\"attacker\"}"))
                .andExpect(status().isBadRequest());
        verify(workspaces, times(1)).create("s1", "b1", sha, "key");
    }
}
