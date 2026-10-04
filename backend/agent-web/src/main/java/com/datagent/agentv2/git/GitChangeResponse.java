package com.datagent.agentv2.git;

import com.datagent.agentv2.git.application.GitSnapshotStore;
import com.datagent.agentv2.git.model.*;
import java.util.List;

/**
 * 卡片独立查询契约；文件列表只包含登记标识与摘要，不携带 diff 或服务端绝对路径。
 *
 * @param version 按钮 CAS 版本，客户端每次用最新详情值，不自行递增
 * @param commitId 已生成提交的对象标识；尚未生成时为 null，存在不代表已经推送
 * @param reviewAvailable 本次读取的冻结审阅材料是否完整可用；false 时必须禁用确认和重试
 * @param files 审阅材料不可用时为 null，不表示空变更；可用时提供完整文件摘要
 * @param errorSummary 由稳定错误码解释的安全提示，绝不使用 Git stderr
 * @param validation 已冻结的 dbt 定义验证摘要；历史或非 dbt 候选为空，不代表构建或发布状态
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
        boolean reviewAvailable,
        List<FileSummary> files,
        String errorCode,
        String errorSummary,
        ValidationSummary validation,
        /** 候选固定工作区，不随页面当前选择变化。 */
        String workspaceId,
        /** 固定项目，历史候选为空。 */
        String projectId,
        /** 固定分支实例，历史候选为空。 */
        String branchId,
        /** 固定完整引用，历史候选为空。 */
        String gitRef) {
    public static GitChangeResponse composeFromChange(
            GitChangeView change, List<GitSnapshotStore.SnapshotFile> files) {
        return compose(
                change,
                files.stream()
                        .map(f -> new FileSummary(f.fileId(), f.status(), f.path()))
                        .toList());
    }

    /** 审阅材料不可用时仍返回持久化状态，调用方仅可在已知快照损坏时使用。 */
    public static GitChangeResponse composeFromUnavailableReview(GitChangeView change) {
        return compose(change, null);
    }

    private static GitChangeResponse compose(GitChangeView change, List<FileSummary> files) {
        return new GitChangeResponse(
                change.changeId(),
                change.version(),
                change.status(),
                change.branch(),
                change.baseCommit(),
                change.summary(),
                change.commitMessage(),
                change.commitId(),
                files != null,
                files,
                change.errorCode(),
                GitErrorMessages.summary(change.errorCode()),
                ValidationSummary.composeFromEvidence(change.validation()),
                change.workspaceId(),
                change.projectId(),
                change.branchId(),
                change.gitRef());
    }

    /** fileId 只在当前候选内有意义，供 diff 查询使用，不能解释为文件系统路径。 */
    public record FileSummary(String fileId, String status, String path) {}

    /** 只展示已封存校验范围，不能把此状态当成构建或发布状态。 */
    public record ValidationSummary(
            String status, String baseCommitSha, String validatedAt, List<String> checkedLevels) {
        static ValidationSummary composeFromEvidence(GitValidationEvidence evidence) {
            return evidence == null
                    ? null
                    : new ValidationSummary(
                            "SUCCEEDED",
                            evidence.baseCommitSha(),
                            evidence.validatedAt(),
                            evidence.checkedLevels());
        }
    }
}
