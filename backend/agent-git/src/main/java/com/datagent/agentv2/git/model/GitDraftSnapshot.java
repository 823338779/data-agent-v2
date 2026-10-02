package com.datagent.agentv2.git.model;

import java.util.List;

/** 完整差异与正文均从同一个冻结 tree 读取；网络开始后不再读取可变工作目录。 */
public record GitDraftSnapshot(
        String sessionId,
        String workspaceId,
        String baseCommitSha,
        String treeId,
        long draftRevision,
        List<File> files) {
    public GitDraftSnapshot {
        files = List.copyOf(files);
    }

    /** oldContent/newContent 的 null 分别代表新增/删除，空字符串代表实际空文件。 */
    public record File(String status, String path, String oldContent, String newContent) {}
}
