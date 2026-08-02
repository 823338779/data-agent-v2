package com.datagent.agentv2.conversation.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 一段连续 Agent 对话的业务模型。 */
@Getter
@Setter
@NoArgsConstructor
public final class ConversationSession {

    /** 会话业务唯一标识。 */
    private String conversationSessionId;

    /** 用户可见的会话标题。 */
    private String title;

    /** 会话检索和业务分类标签。 */
    private String tag;

    /** 会话用途类型。 */
    private String sessionType;

    /** 创建会话的用户业务标识。 */
    private String createdByUserId;

    /** 当前已分配的最大消息序号。 */
    private Long conversationMessageSequence;

    /** 当前已分配的最大执行事件序号。 */
    private Long executionEventSequence;

    /** 最近一次影响会话内容或列表状态的活动时间。 */
    private Instant lastActivityAt;

    /** 最近一个会触发未读提示的执行事件序号。 */
    private Long lastNotifiableEventSequence;

    /** 会话乐观锁版本。 */
    private Long version;

    /** 用户可见的会话创建时间。 */
    private LocalDateTime createdAt;

    /** 用户可见的会话最后更新时间。 */
    private LocalDateTime updatedAt;

    /** 组装新建会话，并集中设置业务初始水位。 */
    public static ConversationSession create(
            String conversationSessionId,
            String title,
            String tag,
            String sessionType,
            String createdByUserId,
            Instant createdAt) {
        ConversationSession session = new ConversationSession();
        session.conversationSessionId = conversationSessionId;
        session.title = title;
        session.tag = tag;
        session.sessionType = sessionType;
        session.createdByUserId = createdByUserId;
        session.conversationMessageSequence = 0L;
        session.executionEventSequence = 0L;
        session.lastActivityAt = createdAt;
        session.lastNotifiableEventSequence = 0L;
        session.version = 0L;
        return session;
    }

    /** 同时推进消息和执行事件序号，返回本次分配的两个序号。 */
    public AllocatedSequences allocateSubmissionSequences() {
        conversationMessageSequence++;
        executionEventSequence++;
        return new AllocatedSequences(conversationMessageSequence, executionEventSequence);
    }

    /** 一次消息提交所分配的会话级序号。 */
    public record AllocatedSequences(long conversationMessage, long executionEvent) {}
}
