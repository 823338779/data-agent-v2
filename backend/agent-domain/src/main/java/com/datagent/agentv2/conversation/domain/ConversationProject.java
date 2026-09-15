package com.datagent.agentv2.conversation.domain;

import java.time.LocalDateTime;

/** 仅用于会话侧栏组织的一级分组，不承载共享上下文或执行配置。 */
public record ConversationProject(
        String projectId,
        /** 去除首尾空白后的名称，1～60 个 UTF-16 代码单元，允许同名。 */
        String name,
        /** 项目改名和删除的版本，独立于会话共享版本。 */
        long version,
        /** 新建模型为空，保存时填充；展示采用当前服务的北京时间。 */
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
    public ConversationProject {
        name = name == null ? "" : name.trim();
        if (name.isEmpty() || name.length() > 60) {
            throw new IllegalArgumentException("Project name must contain 1-60 characters");
        }
    }

    /** 审计时间由保存时填充，新项目从版本零开始。 */
    public static ConversationProject create(String projectId, String name) {
        return new ConversationProject(projectId, name, 0, null, null);
    }

    public ConversationProject renamed(String name) {
        return new ConversationProject(projectId, name, version, createdAt, updatedAt);
    }
}
