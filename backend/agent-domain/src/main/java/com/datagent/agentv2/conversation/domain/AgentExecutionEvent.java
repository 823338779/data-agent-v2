package com.datagent.agentv2.conversation.domain;

import com.datagent.agentv2.conversation.enums.ExecutionEventType;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Agent 执行时间线中的一条可重放业务事件。 */
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AgentExecutionEvent {

    /** 事件业务标识，重放时保持不变。 */
    private String executionEventId;

    /** 所属会话标识，与会话级事件序号共同定位回放顺序。 */
    private String conversationSessionId;

    /** 产生事件的执行业务标识。 */
    private String executionId;

    /** 会话内单调递增的事件序号，由持久化层原子分配。 */
    private Long executionEventSequence;

    /** 事件类别，决定客户端刷新或终态处理方式。 */
    private ExecutionEventType type;

    /** 事件发生的绝对时刻，不作为回放排序依据。 */
    private Instant occurredAt;

    /** 持久化版本，新事件从 0 开始。 */
    private Long version;

    /** 从完整存储快照恢复领域状态；Builder 只负责组装，构造时统一校验不变量。 */
    @Builder
    private AgentExecutionEvent(
            String executionEventId,
            String conversationSessionId,
            String executionId,
            Long executionEventSequence,
            ExecutionEventType type,
            Instant occurredAt,
            Long version) {
        this.executionEventId = DomainInvariants.text(executionEventId, "executionEventId");
        this.conversationSessionId =
                DomainInvariants.text(conversationSessionId, "conversationSessionId");
        this.executionId = DomainInvariants.text(executionId, "executionId");
        this.executionEventSequence =
                DomainInvariants.nonnegative(executionEventSequence, "executionEventSequence");
        this.type = java.util.Objects.requireNonNull(type, "type");
        this.occurredAt = java.util.Objects.requireNonNull(occurredAt, "occurredAt");
        this.version = DomainInvariants.nonnegative(version, "version");
    }

    public static AgentExecutionEvent occurred(
            String eventId,
            String sessionId,
            String executionId,
            long sequence,
            ExecutionEventType type,
            Instant occurredAt) {
        return builder()
                .executionEventId(eventId)
                .conversationSessionId(sessionId)
                .executionId(executionId)
                .executionEventSequence(sequence)
                .type(type)
                .occurredAt(occurredAt)
                .version(0L)
                .build();
    }
}
