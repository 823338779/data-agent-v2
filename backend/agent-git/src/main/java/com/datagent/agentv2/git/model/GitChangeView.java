package com.datagent.agentv2.git.model;

import com.datagent.agentv2.persistence.entity.git.GitChangeEntity;

/**
 * 卡片稳定详情；完整 diff 另行按登记文件查询，不进入消息 SSE。
 *
 * @param changeId 候选稳定标识，所有确认、重试和审阅操作均定位到此候选
 * @param workspaceId 候选所属工作区；不能将候选移动到其他会话草稿
 * @param branch 固定目标分支，当前阶段仅为 main
 * @param baseCommit 冻结时的基线提交；远端变化时必须重新确认
 * @param candidateDigest 绑定远端、分支、基线、tree、提交说明和完整 diff 的 SHA-256 摘要
 * @param summary 卡片供用户审阅的变更摘要
 * @param commitMessage 冻结后不可替换的提交说明
 * @param draftRevision 冻结时的草稿内容修订号；不同于候选实体的 CAS 版本
 * @param fileCount 候选包含的变更文件总数
 * @param status 候选授权与提交状态；只有用户确认后才能进入提交阶段
 * @param version 候选实体 CAS 版本，按钮操作须携带当前版本，状态变化时推进
 * @param commitId 已登记的提交对象 ID；尚未生成/登记时为 null，登记不等于推送成功
 * @param errorCode 最近失败或待核实的稳定分类；无错误时为 null，不包含原始 Git 输出
 */
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
