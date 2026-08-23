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

    /** 当前已分配的最大消息序号。 */
    private Long conversationMessageSequence;

    /** 当前已分配的最大执行事件序号。 */
    private Long executionEventSequence;

    /** 最近一次影响会话内容或列表状态的活动时间。 */
    private Instant lastActivityAt;

    /** 会话乐观锁版本。 */
    private Long version;

    /** 用户可见的会话创建时间。 */
    private LocalDateTime createdAt;

    /** 用户可见的会话最后更新时间。 */
    private LocalDateTime updatedAt;

    /** 组装新建会话，并集中设置业务初始水位。 */
    public static ConversationSession create(
            String conversationSessionId, String title, String tag, Instant createdAt) {
        ConversationSession session = new ConversationSession();
        session.conversationSessionId = conversationSessionId;
        session.title = title;
        session.tag = tag;
        session.conversationMessageSequence = 0L;
        session.executionEventSequence = 0L;
        session.lastActivityAt = createdAt;
        session.version = 0L;
        return session;
    }

    /** 在会话锁内一次推进指定数量的消息序号和一个执行事件序号。 */
    public AllocatedSequences allocateSubmissionSequences(int messageCount) {
        if (messageCount <= 0) {
            throw new IllegalArgumentException("messageCount must be positive");
        }
        long firstConversationMessage = conversationMessageSequence + 1;
        conversationMessageSequence += messageCount;
        executionEventSequence++;
        return new AllocatedSequences(
                firstConversationMessage, conversationMessageSequence, executionEventSequence);
    }

    /** 一次执行创建所分配的连续消息区间和执行事件序号。 */
    public record AllocatedSequences(
            long firstConversationMessage, long lastConversationMessage, long executionEvent) {}
}
