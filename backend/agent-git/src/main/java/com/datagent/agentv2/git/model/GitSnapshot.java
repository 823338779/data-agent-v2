package com.datagent.agentv2.git.model;

import java.util.List;

/**
 * 不可变 Git tree 及其完整审阅材料。
 *
 * @param treeId 冻结的 Git tree 对象 ID；只描述目录内容，不意味着已经创建或推送提交
 * @param candidateDigest 绑定仓库、分支、基线、tree、提交说明和 diff 的 SHA-256 摘要
 * @param diff 当前基线到冻结 tree 的完整补丁；应用层可分页展示但不能截断冻结内容
 * @param changes 冻结变更文件的不可变有序列表，与 diff 使用相同基线和 tree
 */
public record GitSnapshot(
        String treeId, String candidateDigest, String diff, List<GitFileChange> changes) {
    public GitSnapshot {
        changes = List.copyOf(changes);
    }
}
