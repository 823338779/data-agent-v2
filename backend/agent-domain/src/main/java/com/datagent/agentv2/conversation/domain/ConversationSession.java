package com.datagent.agentv2.conversation.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 一段连续 Agent 对话的业务模型。 */
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ConversationSession {

    /** 会话业务唯一标识。 */
    private String conversationSessionId;

    /** 用户可见的会话标题。 */
    private String title;

    /** 会话检索和业务分类标签。 */
    private String tag;

    /** 侧栏项目归属；空表示未分组，不参与上下文选择。 */
    private String projectId;

    /** 置顶时间；空表示未置顶，与项目归属互相独立。 */
    private Instant pinnedAt;

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

    /** 从完整存储快照恢复领域状态；Builder 只负责组装，构造时统一校验不变量。 */
    @Builder
    private ConversationSession(
            String conversationSessionId,
            String title,
            String tag,
            String projectId,
            Instant pinnedAt,
            Long conversationMessageSequence,
            Long executionEventSequence,
            Instant lastActivityAt,
            Long version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
        this.conversationSessionId =
                DomainInvariants.text(conversationSessionId, "conversationSessionId");
        this.title = DomainInvariants.text(title, "title");
        this.tag = tag;
        this.projectId = projectId;
        this.pinnedAt = pinnedAt;
        this.conversationMessageSequence =
                DomainInvariants.nonnegative(
                        conversationMessageSequence, "conversationMessageSequence");
        this.executionEventSequence =
                DomainInvariants.nonnegative(executionEventSequence, "executionEventSequence");
        this.lastActivityAt = java.util.Objects.requireNonNull(lastActivityAt, "lastActivityAt");
        this.version = DomainInvariants.nonnegative(version, "version");
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 组装新建会话，并集中设置业务初始水位。 */
    public static ConversationSession create(
            String conversationSessionId, String title, String tag, Instant createdAt) {
        ConversationSession session = new ConversationSession();
        session.conversationSessionId =
                DomainInvariants.text(conversationSessionId, "conversationSessionId");
        session.title = DomainInvariants.text(title, "title");
        session.tag = tag;
        session.conversationMessageSequence = 0L;
        session.executionEventSequence = 0L;
        session.lastActivityAt = java.util.Objects.requireNonNull(createdAt, "createdAt");
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

    /** 用户修改标题，空标题不得覆盖已有可展示标题。 */
    public void rename(String title) {
        this.title = DomainInvariants.text(title, "title");
    }

    /** 新建会话时指定分组；空值明确表示未分组。 */
    public void assignProject(String projectId) {
        this.projectId = projectId == null ? null : DomainInvariants.text(projectId, "projectId");
    }

    /** 仅回写提交后的审计字段和版本，避免存储映射绕过业务状态迁移。 */
    public void acknowledgePersistence(
            long persistedVersion, LocalDateTime createdAt, LocalDateTime updatedAt) {
        version = DomainInvariants.advancedVersion(version, persistedVersion);
        this.createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = java.util.Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** 一次执行创建所分配的连续消息区间和执行事件序号。 */
    public record AllocatedSequences(
            long firstConversationMessage, long lastConversationMessage, long executionEvent) {}
}
