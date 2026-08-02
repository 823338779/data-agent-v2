package com.datagent.agentv2.conversation.domain;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Agent 执行时间线中的一条可重放业务事件。 */
@Getter
@Setter
@NoArgsConstructor
public final class AgentExecutionEvent {

    /** 执行事件业务唯一标识。 */
    private String executionEventId;

    /** 事件所属会话标识。 */
    private String conversationSessionId;

    /** 事件所属 Agent 执行标识。 */
    private String executionId;

    /** 事件在会话内的单调递增序号。 */
    private Long executionEventSequence;

    /** 稳定事件类型。 */
    private String type;

    /** 事件 JSON 负载。 */
    private String payload;

    /** 业务事件实际发生时间。 */
    private Instant occurredAt;

    /** 事件乐观锁版本。 */
    private Long version;

    /** 组装一条新的执行事件。 */
    public static AgentExecutionEvent occurred(
            String eventId,
            String sessionId,
            String executionId,
            long sequence,
            String type,
            String payload,
            Instant occurredAt) {
        AgentExecutionEvent event = new AgentExecutionEvent();
        event.executionEventId = eventId;
        event.conversationSessionId = sessionId;
        event.executionId = executionId;
        event.executionEventSequence = sequence;
        event.type = type;
        event.payload = payload;
        event.occurredAt = occurredAt;
        event.version = 0L;
        return event;
    }
}
