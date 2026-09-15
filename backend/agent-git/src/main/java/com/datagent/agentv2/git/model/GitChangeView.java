package com.datagent.agentv2.git.model;

import com.datagent.agentv2.persistence.entity.git.GitChangeEntity;

/** 卡片稳定详情；完整 diff 另行按登记文件查询，不进入消息 SSE。 */
public record GitChangeView(
        String changeId,
        String workspaceId,
        String branch,
        String baseCommit,
        String candidateDigest,
        String summary,
        String commitMessage,
        long draftRevision,
        int fileCount,
        GitChangeStatus status,
        long version,
        String commitId,
        String errorCode) {
    public static GitChangeView composeFromChange(GitChangeEntity c) {
        return new GitChangeView(
                c.getChangeId(),
                c.getWorkspaceId(),
                c.getBranch(),
                c.getBaseCommit(),
                c.getCandidateDigest(),
                c.getSummary(),
                c.getCommitMessage(),
                c.getDraftRevision(),
                c.getFileCount(),
                GitChangeStatus.valueOf(c.getStatus()),
                c.getVersion(),
                c.getCommitId(),
                c.getErrorCode());
    }
}
