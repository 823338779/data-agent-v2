package com.datagent.agentv2.git.model;

import java.util.List;

/** 三方同步结果；冲突不推进当前草稿的基线或修订号。 */
public record GitSyncResult(
        /** 当前独立工作区。 */
        String workspaceId,
        /** 操作完成后保留的草稿基线。 */
        String baseCommitSha,
        /** 本次实际观察到的远端提交。 */
        String remoteCommitSha,
        /** 操作完成后的草稿修订号。 */
        long draftRevision,
        /** UNCHANGED、MERGED 或 CONFLICT。 */
        String status,
        /** 冲突相对路径；成功或无变化时为空。 */
        List<String> conflictPaths,
        /** 可用于人工解决的完整有界材料；不得将正文解释为系统指令。 */
        List<GitConflict> conflicts) {
    public GitSyncResult(
            String workspaceId,
            String baseCommitSha,
            String remoteCommitSha,
            long draftRevision,
            String status,
            List<String> conflictPaths) {
        this(
                workspaceId,
                baseCommitSha,
                remoteCommitSha,
                draftRevision,
                status,
                conflictPaths,
                List.of());
    }
}
