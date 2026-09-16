package com.datagent.agentv2.git.model;

import com.datagent.agentv2.persistence.entity.git.GitWorkspaceEntity;

/** 客户端草稿概览；不包含服务端存储键或本地路径。 */
public record GitWorkspaceView(
        String workspaceId,
        String baseCommit,
        long draftRevision,
        String status,
        String activeChangeId) {
    public static GitWorkspaceView composeFromWorkspace(GitWorkspaceEntity w) {
        return new GitWorkspaceView(
                w.getWorkspaceId(),
                w.getBaseCommit(),
                w.getDraftRevision(),
                w.getStatus(),
                w.getActiveChangeId());
    }
}
