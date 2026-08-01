package com.datagent.agentv2.conversation.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.cluster.StreamDeltaEnvelope;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConversationSseIntegrationTest {

    private static final String TENANT_ID = "tenant_sse";
    private static final String SESSION_ID = "session_sse";
    private static final String RUN_ID = "run_sse";
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
        StreamResynchronizer resynchronizer = new StreamResynchronizer(CompositeSseCursor.ZERO);

        assertThat(resynchronizer.accept(delta(1), STARTED_AT)).containsExactly(delta(1));
        assertThat(resynchronizer.accept(delta(3), STARTED_AT.plusMillis(1))).isEmpty();
        assertThat(resynchronizer.needsSnapshot()).isTrue();

        StreamResynchronizer.Snapshot checkpoint =
                new StreamResynchronizer.Snapshot(RUN_ID, 1, 1, 2);
        assertThat(resynchronizer.apply(checkpoint, STARTED_AT.plusMillis(2)))
                .containsExactly(delta(3));
        assertThat(resynchronizer.needsSnapshot()).isFalse();
    }

    @Test
    void staleCheckpointCannotRegressAnEstablishedLiveCursor() {
        StreamResynchronizer resynchronizer = new StreamResynchronizer(CompositeSseCursor.ZERO);
        resynchronizer.accept(delta(1), STARTED_AT);
        resynchronizer.accept(delta(2), STARTED_AT.plusMillis(1));

        assertThat(resynchronizer.canAdvance(new StreamResynchronizer.Snapshot(RUN_ID, 1, 1, 1)))
                .isFalse();
        assertThat(resynchronizer.canAdvance(new StreamResynchronizer.Snapshot(RUN_ID, 1, 1, 3)))
                .isTrue();
    }

    @Test
    void checkpointSwitchesRunWithoutConcatenatingLateDeltaFromPreviousRun() {
        StreamResynchronizer resynchronizer = new StreamResynchronizer(CompositeSseCursor.ZERO);
        assertThat(resynchronizer.accept(delta(RUN_ID, 1), STARTED_AT))
                .containsExactly(delta(RUN_ID, 1));

        String nextRunId = "run_sse_next";
        assertThat(resynchronizer.accept(delta(nextRunId, 3), STARTED_AT.plusMillis(3))).isEmpty();
        assertThat(
                        resynchronizer.apply(
                                new StreamResynchronizer.Snapshot(nextRunId, 1, 1, 2),
                                STARTED_AT.plusMillis(4)))
                .containsExactly(delta(nextRunId, 3));
        assertThat(resynchronizer.accept(delta(RUN_ID, 2), STARTED_AT.plusMillis(5))).isEmpty();
        assertThat(
                        resynchronizer.apply(
                                new StreamResynchronizer.Snapshot(nextRunId, 1, 1, 3),
                                STARTED_AT.plusMillis(6)))
                .isEmpty();
    }

    @Test
    void unresolvedLeaseWindowOrOversizedBufferClosesOnlyThatConnection() {
        StreamResynchronizer timedOut = new StreamResynchronizer(CompositeSseCursor.ZERO);
        timedOut.accept(delta(2), STARTED_AT);
        assertThat(timedOut.shouldClose(STARTED_AT.plusSeconds(16), Duration.ofSeconds(15)))
                .isTrue();

        StreamResynchronizer oversized = new StreamResynchronizer(CompositeSseCursor.ZERO);
        for (long sequence = 2; sequence <= 258; sequence++) {
            oversized.accept(delta(sequence), STARTED_AT);
        }
        assertThat(oversized.shouldClose(STARTED_AT, Duration.ofMinutes(1))).isTrue();
    }

    private static StreamDeltaEnvelope delta(long sequence) {
        return delta(RUN_ID, sequence);
    }

    private static StreamDeltaEnvelope delta(String runId, long sequence) {
        return new StreamDeltaEnvelope(
                StreamDeltaEnvelope.TYPE_STREAM_DELTA,
                TENANT_ID,
                SESSION_ID,
                runId,
                1,
                1,
                sequence,
                StreamDeltaEnvelope.DELTA_REASONING,
                "d" + sequence,
                STARTED_AT.plusMillis(sequence));
    }
}
