package com.datagent.agentv2.conversation.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.cluster.AgentExecutionDeltaNotification;
import com.datagent.agentv2.conversation.cluster.ExecutionDeltaNotificationType;
import com.datagent.agentv2.conversation.cluster.ExecutionDeltaType;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConversationSseIntegrationTest {

    private static final String TENANT_ID = "tenant_sse";
    private static final String SESSION_ID = "session_sse";
    private static final String EXECUTION_ID = "execution_sse";
    private static final Instant STARTED_AT = Instant.parse("2026-08-01T06:00:00Z");

    @Test
    void compositeCursorRoundTripsPersistedAndStreamPositions() {
        CompositeSseCursor cursor = new CompositeSseCursor(4, 2, 3, 9);

        assertThat(cursor.encode()).isEqualTo("e=4;a=2;t=3;s=9");
        assertThat(CompositeSseCursor.parse(cursor.encode())).isEqualTo(cursor);
        assertThat(CompositeSseCursor.parse(null)).isEqualTo(CompositeSseCursor.ZERO);
    }

    @Test
    void gapWaitsForCheckpointThenFlushesBufferedDeltaWithoutWrongConcatenation() {
        ExecutionStreamResynchronizer resynchronizer =
                new ExecutionStreamResynchronizer(CompositeSseCursor.ZERO);

        assertThat(resynchronizer.accept(delta(1), STARTED_AT)).containsExactly(delta(1));
        assertThat(resynchronizer.accept(delta(3), STARTED_AT.plusMillis(1))).isEmpty();
        assertThat(resynchronizer.needsSnapshot()).isTrue();

        ExecutionStreamResynchronizer.Snapshot checkpoint =
                new ExecutionStreamResynchronizer.Snapshot(EXECUTION_ID, 1, 1, 2);
        assertThat(resynchronizer.apply(checkpoint, STARTED_AT.plusMillis(2)))
                .containsExactly(delta(3));
        assertThat(resynchronizer.needsSnapshot()).isFalse();
    }

    @Test
    void staleCheckpointCannotRegressAnEstablishedLiveCursor() {
        ExecutionStreamResynchronizer resynchronizer =
                new ExecutionStreamResynchronizer(CompositeSseCursor.ZERO);
        resynchronizer.accept(delta(1), STARTED_AT);
        resynchronizer.accept(delta(2), STARTED_AT.plusMillis(1));

        assertThat(
                        resynchronizer.canAdvance(
                                new ExecutionStreamResynchronizer.Snapshot(EXECUTION_ID, 1, 1, 1)))
                .isFalse();
        assertThat(
                        resynchronizer.canAdvance(
                                new ExecutionStreamResynchronizer.Snapshot(EXECUTION_ID, 1, 1, 3)))
                .isTrue();
    }

    @Test
    void checkpointSwitchesExecutionWithoutConcatenatingLateDeltaFromPreviousExecution() {
        ExecutionStreamResynchronizer resynchronizer =
                new ExecutionStreamResynchronizer(CompositeSseCursor.ZERO);
        assertThat(resynchronizer.accept(delta(EXECUTION_ID, 1), STARTED_AT))
                .containsExactly(delta(EXECUTION_ID, 1));

        String nextExecutionId = "execution_sse_next";
        assertThat(resynchronizer.accept(delta(nextExecutionId, 3), STARTED_AT.plusMillis(3)))
                .isEmpty();
        assertThat(
                        resynchronizer.apply(
                                new ExecutionStreamResynchronizer.Snapshot(
                                        nextExecutionId, 1, 1, 2),
                                STARTED_AT.plusMillis(4)))
                .containsExactly(delta(nextExecutionId, 3));
        assertThat(resynchronizer.accept(delta(EXECUTION_ID, 2), STARTED_AT.plusMillis(5)))
                .isEmpty();
        assertThat(
                        resynchronizer.apply(
                                new ExecutionStreamResynchronizer.Snapshot(
                                        nextExecutionId, 1, 1, 3),
                                STARTED_AT.plusMillis(6)))
                .isEmpty();
    }

    @Test
    void unresolvedLeaseWindowOrOversizedBufferClosesOnlyThatConnection() {
        ExecutionStreamResynchronizer timedOut =
                new ExecutionStreamResynchronizer(CompositeSseCursor.ZERO);
        timedOut.accept(delta(2), STARTED_AT);
        assertThat(timedOut.shouldClose(STARTED_AT.plusSeconds(16), Duration.ofSeconds(15)))
                .isTrue();

        ExecutionStreamResynchronizer oversized =
                new ExecutionStreamResynchronizer(CompositeSseCursor.ZERO);
        for (long sequence = 2; sequence <= 258; sequence++) {
            oversized.accept(delta(sequence), STARTED_AT);
        }
        assertThat(oversized.shouldClose(STARTED_AT, Duration.ofMinutes(1))).isTrue();
    }

    private static AgentExecutionDeltaNotification delta(long sequence) {
        return delta(EXECUTION_ID, sequence);
    }

    private static AgentExecutionDeltaNotification delta(String executionId, long sequence) {
        return new AgentExecutionDeltaNotification(
                ExecutionDeltaNotificationType.STREAM_DELTA,
                TENANT_ID,
                SESSION_ID,
                executionId,
                1,
                1,
                sequence,
                ExecutionDeltaType.REASONING,
                "d" + sequence,
                STARTED_AT.plusMillis(sequence));
    }
}
