package com.datagent.agentv2.conversation.session;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.ConversationSession;
import com.datagent.agentv2.conversation.enums.SessionExecutionStatus;
import java.time.Instant;
import java.time.LocalDateTime;

/** 会话列表使用的轻量投影，不携带消息正文、执行时间线或回答内容。 */
public record ConversationSummaryView(
        /** 会话业务唯一标识。 */
        String conversationSessionId,
        /** 用户可见标题。 */
        String title,
        /** 会话检索标签。 */
        String tag,
        /** 项目归属；空表示未分组，置顶不会清除此字段。 */
        String projectId,
        /** 置顶时间；空表示未置顶。 */
        Instant pinnedAt,
        /** 会话创建时间。 */
        LocalDateTime createdAt,
        /** 会话最后更新时间。 */
        LocalDateTime updatedAt,
        /** 会话共享字段乐观锁版本。 */
        long version,
        /** 会话最新执行状态，无执行时为 idle。 */
        SessionExecutionStatus status) {

    /** 从会话及其最新执行组装列表投影。 */
    public static ConversationSummaryView composeFromSession(
            ConversationSession session, AgentExecution latestExecution) {
        return new ConversationSummaryView(
                session.getConversationSessionId(),
                session.getTitle(),
                session.getTag() == null ? "" : session.getTag(),
                session.getProjectId(),
                session.getPinnedAt(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                session.getVersion(),
                latestExecution == null
                        ? SessionExecutionStatus.IDLE
                        : SessionExecutionStatus.fromExecutionStatus(latestExecution.getStatus()));
    }
}
