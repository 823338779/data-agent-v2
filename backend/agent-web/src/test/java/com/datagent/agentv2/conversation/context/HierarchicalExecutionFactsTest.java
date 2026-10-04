package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.persistence.entity.git.GitChangeEntity;
import com.datagent.agentv2.persistence.entity.git.GitExecutionWorkspaceEntity;
import com.datagent.agentv2.persistence.entity.git.GitWorkspaceEntity;
import com.datagent.agentv2.persistence.service.git.GitChangePersistenceService;
import com.datagent.agentv2.persistence.service.git.GitExecutionWorkspaceService;
import com.datagent.agentv2.persistence.service.git.GitWorkspacePersistenceService;
import org.junit.jupiter.api.Test;

class HierarchicalExecutionFactsTest {
    private static final String ID = "id";
    private static final String BRANCH = "branch-a";
    private static final String PENDING = "PENDING_CONFIRMATION";

    @Test
    void candidateDoesNotBecomeConfirmedPush() {
        var executions = mock(GitExecutionWorkspaceService.class);
        var workspaces = mock(GitWorkspacePersistenceService.class);
        var changes = mock(GitChangePersistenceService.class);
        var binding = new GitExecutionWorkspaceEntity();
        binding.setWorkspaceId(ID);
        binding.setBranchId(BRANCH);
        binding.setProjectId(ID);
        var workspace = new GitWorkspaceEntity();
        workspace.setWorkspaceId(ID);
        workspace.setDraftRevision(5L);
        workspace.setActiveChangeId(ID);
        var change = new GitChangeEntity();
        change.setChangeId(ID);
        change.setWorkspaceId(ID);
        change.setStatus(PENDING);
        when(executions.findByExecutionId(ID)).thenReturn(binding);
        when(workspaces.findByWorkspaceId(ID)).thenReturn(workspace);
        when(changes.find(ID)).thenReturn(change);
        var facts = new ContextExecutionFactsFactory(executions, workspaces, changes).create(ID);
        assertThat(facts.values())
                .containsEntry("branchId", BRANCH)
                .containsEntry("draftRevision", "5")
                .containsEntry("changeStatus", PENDING)
                .containsEntry("pushConfirmed", "false");
    }
}
