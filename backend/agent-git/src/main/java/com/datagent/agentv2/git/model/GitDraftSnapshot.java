package com.datagent.agentv2.git.model;

import java.util.List;

/** 完整差异与正文均从同一个冻结 tree 读取；网络开始后不再读取可变工作目录。 */
public record GitDraftSnapshot(
        String sessionId,
        String workspaceId,
        String baseCommitSha,
        String treeId,
        long draftRevision,
        List<File> files,
        /** 固定项目，历史草稿为空。 */
        String projectId,
        /** 固定分支实例，历史草稿为空。 */
        String branchId,
        /** 固定完整引用，历史草稿为空。 */
        String gitRef) {
    public GitDraftSnapshot {
        files = List.copyOf(files);
    }

    public GitDraftSnapshot(
            String sessionId,
            String workspaceId,
            String baseCommitSha,
            String treeId,
            long draftRevision,
            List<File> files) {
        this(sessionId, workspaceId, baseCommitSha, treeId, draftRevision, files, null, null, null);
    }

    /** 同一工作区可被多会话使用，证据审计归属于本次可信调用会话。 */
    public GitDraftSnapshot forSession(String session) {
        return new GitDraftSnapshot(
                session,
                workspaceId,
                baseCommitSha,
                treeId,
                draftRevision,
                files,
                projectId,
                branchId,
                gitRef);
    }

    /** oldContent/newContent 的 null 分别代表新增/删除，空字符串代表实际空文件。 */
    public record File(String status, String path, String oldContent, String newContent) {}
}
