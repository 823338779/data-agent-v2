package com.datagent.agentv2.persistence.entity.development;

/** 旧已推送记录的只读关联；repositoryId 需要部署显式映射，不能冒充仓库地址。 */
public record LegacyDevelopmentSource(
        String projectId,
        String sessionId,
        String workspaceId,
        String repositoryId,
        String gitRef,
        String commitSha) {}
