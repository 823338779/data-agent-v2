package com.datagent.agentv2.git.model;

import com.datagent.agentv2.persistence.entity.git.GitWorkspaceEntity;

/**
 * 客户端草稿概览；不包含服务端存储键或本地路径。
 *
 * @param workspaceId 当前会话工作区稳定标识；重开草稿不改变此标识
 * @param baseCommit 当前草稿基线提交，成功推送后推进
 * @param draftRevision 内容变化或显式重开时推进的草稿修订号，用于拒绝旧候选冻结请求
 * @param status READY 可用、MUTATING 变更中、BROKEN 完整性失效；失效后必须显式重开
 * @param activeChangeId 当前关联候选标识；尚无候选或显式重开后为 null，具体可操作性由候选状态决定
 */
public record GitWorkspaceView(
        String workspaceId,
        String baseCommit,
        long draftRevision,
        String status,
        String activeChangeId,
        /** dbt 服务登记的项目；历史工作区为空。 */
        String projectId,
        /** 创建时固定的分支实例，删除重建后不能复用。 */
        String branchId,
        /** 固定完整远端引用。 */
        String gitRef) {
    public GitWorkspaceView(
            String workspaceId,
            String baseCommit,
            long draftRevision,
            String status,
            String activeChangeId) {
        this(workspaceId, baseCommit, draftRevision, status, activeChangeId, null, null, null);
    }

    public static GitWorkspaceView composeFromWorkspace(GitWorkspaceEntity w) {
        return new GitWorkspaceView(
                w.getWorkspaceId(),
                w.getBaseCommit(),
                w.getDraftRevision(),
                w.getStatus(),
                w.getActiveChangeId(),
                w.getProjectId(),
                w.getBranchId(),
                w.getGitRef());
    }
}
