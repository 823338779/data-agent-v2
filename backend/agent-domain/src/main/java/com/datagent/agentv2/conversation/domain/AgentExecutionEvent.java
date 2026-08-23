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

    private String executionEventId;
    private String conversationSessionId;
    private String executionId;
    private Long executionEventSequence;
    private String type;
    private Instant occurredAt;
    private Long version;

    public static AgentExecutionEvent occurred(
            String eventId,
            String sessionId,
            String executionId,
            long sequence,
            String type,
            Instant occurredAt) {
        AgentExecutionEvent event = new AgentExecutionEvent();
        event.executionEventId = eventId;
        event.conversationSessionId = sessionId;
        event.executionId = executionId;
        event.executionEventSequence = sequence;
        event.type = type;
        event.occurredAt = occurredAt;
        event.version = 0L;
        return event;
    }
}
