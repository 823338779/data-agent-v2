package com.datagent.agentv2.git;

import com.datagent.agentv2.git.application.GitSnapshotStore;
import com.datagent.agentv2.git.model.*;
import java.util.List;

/**
 * 卡片独立查询契约；文件列表只包含登记标识与摘要，不携带 diff 或服务端绝对路径。
 *
 * @param version 按钮 CAS 版本，客户端每次用最新详情值，不自行递增
 * @param commitId 已生成提交的对象标识；尚未生成时为 null，存在不代表已经推送
 * @param errorSummary 由稳定错误码解释的安全提示，绝不使用 Git stderr
 */
public record GitChangeResponse(
        String changeId,
        long version,
        GitChangeStatus status,
        String branch,
        String baseCommit,
        String summary,
        String commitMessage,
        String commitId,
        List<FileSummary> files,
        String errorCode,
        String errorSummary) {
    public static GitChangeResponse composeFromChange(
            GitChangeView change, List<GitSnapshotStore.SnapshotFile> files) {
        return new GitChangeResponse(
                change.changeId(),
                change.version(),
                change.status(),
                change.branch(),
                change.baseCommit(),
                change.summary(),
                change.commitMessage(),
                change.commitId(),
                files.stream().map(f -> new FileSummary(f.fileId(), f.status(), f.path())).toList(),
                change.errorCode(),
                GitErrorMessages.summary(change.errorCode()));
    }

    /** fileId 只在当前候选内有意义，供 diff 查询使用，不能解释为文件系统路径。 */
    public record FileSummary(String fileId, String status, String path) {}
}
