package com.datagent.agentv2.git.config;

/** 应用层固定仓库和提交身份；启用开关及配置绑定由 server 装配负责。 */
public record GitApplicationProperties(String repositoryId, String authorName, String authorEmail) {
    public GitApplicationProperties {
        if (repositoryId == null
                || repositoryId.isBlank()
                || authorName == null
                || authorName.isBlank()
                || authorEmail == null
                || authorEmail.isBlank()) {
            throw new IllegalArgumentException("Git 应用配置不完整");
        }
    }
}
