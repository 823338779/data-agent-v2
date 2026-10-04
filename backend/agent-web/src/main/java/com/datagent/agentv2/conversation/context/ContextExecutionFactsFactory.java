package com.datagent.agentv2.conversation.context;

import com.datagent.agentv2.context.hierarchical.domain.ContextExecutionFacts;
import com.datagent.agentv2.persistence.service.git.GitChangePersistenceService;
import com.datagent.agentv2.persistence.service.git.GitExecutionWorkspaceService;
import com.datagent.agentv2.persistence.service.git.GitWorkspacePersistenceService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;

/** 读取既有持久化 Service 建立事实快照，不改变候选或确认状态。 */
@RequiredArgsConstructor
public final class ContextExecutionFactsFactory {
    private static final String WORKSPACE = "workspaceId",
            PROJECT = "projectId",
            BRANCH = "branchId",
            REF = "gitRef";
    private static final String REVISION = "draftRevision",
            BASE = "baseCommit",
            CHANGE = "changeId";
    private static final String STATUS = "changeStatus",
            CONFIRMED = "pushConfirmed",
            CONFIRMED_AT = "confirmedAt";
    private static final String CONFIRMATION_VERSION = "confirmationVersion", COMMIT = "commitId";
    private final GitExecutionWorkspaceService executions;
    private final GitWorkspacePersistenceService workspaces;
    private final GitChangePersistenceService changes;

    public ContextExecutionFacts create(String executionId) {
        var binding = executions.findByExecutionId(executionId);
        if (binding == null) {
            return ContextExecutionFacts.empty();
        }
        var values = new LinkedHashMap<String, String>();
        put(values, WORKSPACE, binding.getWorkspaceId());
        put(values, PROJECT, binding.getProjectId());
        put(values, BRANCH, binding.getBranchId());
        put(values, REF, binding.getGitRef());
        var workspace = workspaces.findByWorkspaceId(binding.getWorkspaceId());
        if (workspace != null) {
            put(values, REVISION, workspace.getDraftRevision());
            put(values, BASE, workspace.getBaseCommit());
            var change =
                    workspace.getActiveChangeId() == null
                            ? null
                            : changes.find(workspace.getActiveChangeId());
            if (change != null && binding.getWorkspaceId().equals(change.getWorkspaceId())) {
                put(values, CHANGE, change.getChangeId());
                put(values, STATUS, change.getStatus());
                put(values, CONFIRMED, change.getConfirmedAt() != null);
                put(values, CONFIRMED_AT, change.getConfirmedAt());
                put(values, CONFIRMATION_VERSION, change.getConfirmationVersion());
                put(values, COMMIT, change.getCommitId());
            }
        }
        return new ContextExecutionFacts(values);
    }

    private static void put(Map<String, String> values, String key, Object value) {
        if (value != null) {
            values.put(key, String.valueOf(value));
        }
    }
}
