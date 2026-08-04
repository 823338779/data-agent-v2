package com.datagent.agentv2.conversation.session;

import com.datagent.agentv2.conversation.enums.SessionActivityStatus;
import java.time.LocalDateTime;

/** 会话列表使用的轻量投影，不携带消息正文、执行时间线或回答内容。 */
public record ConversationSummaryView(
        /** 会话业务唯一标识。 */
        String conversationSessionId,
        /** 用户可见标题。 */
        String title,
        /** 会话检索标签。 */
        String tag,
        /** 会话创建时间。 */
        LocalDateTime createdAt,
        /** 会话最后更新时间。 */
        LocalDateTime updatedAt,
        /** 会话共享字段乐观锁版本。 */
        long version,
        /** 当前用户是否存在未读通知。 */
        boolean unread,
        /** 最近一个可通知执行事件序号。 */
        long lastNotifiableEventSequence,
        /** 当前用户的会话排序位置。 */
        long sortOrder,
        /** 当前用户会话集合的排序版本。 */
        long orderingVersion,
        /** 会话最新执行状态，无执行时为 idle。 */
        SessionActivityStatus status) {}
