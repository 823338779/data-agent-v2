package com.datagent.agentv2.conversation.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.stream.ExecutionStreamCheckpointCoordinator;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class AgentExecutionDeltaNotificationMapperTest {

    private final AgentExecutionDeltaNotificationMapper mapper =
            Mappers.getMapper(AgentExecutionDeltaNotificationMapper.class);

    @Test
    void mapsAnswerDeltaToNotificationDelta() {
        Instant occurredAt = Instant.parse("2026-08-22T10:00:00Z");
        ExecutionStreamCheckpointCoordinator.AnswerDelta published =
                new ExecutionStreamCheckpointCoordinator.AnswerDelta(
                        "tenant_1", "session_1", "execution_1", 2L, 3L, 4L, "answer", occurredAt);

        AgentExecutionDeltaNotification notification = mapper.toNotification(published);

        assertThat(notification.tenantId()).isEqualTo("tenant_1");
        assertThat(notification.conversationSessionId()).isEqualTo("session_1");
        assertThat(notification.executionId()).isEqualTo("execution_1");
        assertThat(notification.executionAttemptNumber()).isEqualTo(2L);
        assertThat(notification.modelInvocationSequence()).isEqualTo(3L);
        assertThat(notification.outputDeltaSequence()).isEqualTo(4L);
        assertThat(notification.delta()).isEqualTo("answer");
        assertThat(notification.occurredAt()).isEqualTo(occurredAt);
    }
}
