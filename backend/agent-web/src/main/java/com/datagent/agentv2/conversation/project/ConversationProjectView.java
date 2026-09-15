package com.datagent.agentv2.conversation.project;

import com.datagent.agentv2.conversation.domain.ConversationProject;
import java.time.LocalDateTime;

/** 侧栏项目的公开元数据，不含任何会话内容。 */
public record ConversationProjectView(
        String projectId,
        String name,
        /** 项目改名和删除所需的版本；不会因组内消息活动推进。 */
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
    public static ConversationProjectView composeFromProject(ConversationProject project) {
        return new ConversationProjectView(
                project.projectId(),
                project.name(),
                project.version(),
                project.createdAt(),
                project.updatedAt());
    }
}
