package com.datagent.agentv2.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationOutboxMessageEntityTest {

    @Test
    void composesNewNotificationWithInitialVersion() {
        Instant nextAttemptAt = Instant.parse("2026-08-22T10:00:00Z");

        NotificationOutboxMessageEntity notification =
                NotificationOutboxMessageEntity.compose(
                        "notification_1",
                        "execution_cancellation_requested",
                        "agent_execution",
                        "execution_1",
                        3L,
                        "{\"type\":\"cancel\"}",
                        nextAttemptAt);

        assertThat(notification.getNotificationMessageId()).isEqualTo("notification_1");
        assertThat(notification.getNotificationType())
                .isEqualTo("execution_cancellation_requested");
        assertThat(notification.getAggregateType()).isEqualTo("agent_execution");
        assertThat(notification.getAggregateId()).isEqualTo("execution_1");
        assertThat(notification.getAggregateVersion()).isEqualTo(3L);
        assertThat(notification.getPayload()).isEqualTo("{\"type\":\"cancel\"}");
        assertThat(notification.getNextAttemptAt()).isEqualTo(nextAttemptAt);
        assertThat(notification.getVersion()).isZero();
    }
}
