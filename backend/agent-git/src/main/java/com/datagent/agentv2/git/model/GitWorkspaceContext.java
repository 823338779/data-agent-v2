package com.datagent.agentv2.git.model;

/**
 * 可信执行记录冻结的开发目标，模型和 UI 当前选择均不能覆盖。
 *
 * @param workspaceId 独立草稿身份
 * @param projectId 受控 dbt 逻辑项目
 * @param branchId dbt 服务登记的分支实例
 * @param gitRef 创建时固定的完整 Git 引用
 */
public record GitWorkspaceContext(
        String workspaceId, String projectId, String branchId, String gitRef) {}
