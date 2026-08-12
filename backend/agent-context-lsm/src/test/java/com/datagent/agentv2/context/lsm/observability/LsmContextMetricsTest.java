package com.datagent.agentv2.context.lsm.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan.OperationType;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LsmContextMetricsTest {

    private static final String TAG_LEVEL = "level";
    private static final String TAG_RESULT = "result";
    private static final String TAG_TYPE = "type";
    private static final String RESULT_PUBLISHED = "published";
    private static final String TYPE_SEAL_L0 = "seal_l0";
    private static final String SESSION_A = "session-a";
    private static final String SESSION_B = "session-b";
    private static final String SESSION_C = "session-c";

    @Test
    void recordsApprovedMeasurementsWithLowCardinalityTagsOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LsmContextMetrics metrics = new LsmContextMetrics(registry);

        // 一次完整观测覆盖输入压力、活动层级、压缩结果、候选压缩率和冲突计数。
        metrics.recordInput(48_000, 0.75d);
        metrics.recordActiveSegments(SESSION_A, Map.of(0, 4, 2, 1));
        metrics.recordCompaction(
                LsmContextMetrics.CompactionResult.PUBLISHED,
                OperationType.SEAL_L0,
                Duration.ofMillis(250));
        metrics.recordSummary(2, 1_000, 250);
        metrics.recordEstimationError(0.05d);
        metrics.recordManifestCasConflict();
        metrics.recordWindowExceeded();

        assertThat(registry.get("agent_context_input_estimated_tokens").gauge().value())
                .isEqualTo(48_000d);
        assertThat(registry.get("agent_context_utilization_ratio").gauge().value())
                .isEqualTo(0.75d);
        assertThat(
                        registry.get("agent_context_active_segments")
                                .tag(TAG_LEVEL, "0")
                                .gauge()
                                .value())
                .isEqualTo(4d);
        assertThat(
                        registry.get("agent_context_compaction_total")
                                .tag(TAG_RESULT, RESULT_PUBLISHED)
                                .tag(TAG_TYPE, TYPE_SEAL_L0)
                                .counter()
                                .count())
                .isEqualTo(1d);
        assertThat(
                        registry.get("agent_context_compaction_duration_seconds")
                                .tag(TAG_TYPE, TYPE_SEAL_L0)
                                .timer()
                                .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(250d);
        assertThat(
                        registry.get("agent_context_compaction_input_tokens")
                                .tag(TAG_LEVEL, "2")
                                .summary()
                                .totalAmount())
                .isEqualTo(1_000d);
        assertThat(
                        registry.get("agent_context_compaction_output_tokens")
                                .tag(TAG_LEVEL, "2")
                                .summary()
                                .totalAmount())
                .isEqualTo(250d);
        assertThat(
                        registry.get("agent_context_compression_ratio")
                                .tag(TAG_LEVEL, "2")
                                .summary()
                                .totalAmount())
                .isEqualTo(0.25d);
        assertThat(registry.get("agent_context_estimation_error_ratio").gauge().value())
                .isEqualTo(0.05d);
        assertThat(registry.get("agent_context_manifest_cas_conflicts_total").counter().count())
                .isEqualTo(1d);
        assertThat(registry.get("agent_context_window_exceeded_total").counter().count())
                .isEqualTo(1d);

        // 所有动态标签都被限制在设计批准的三个低基数字段内。
        assertThat(registry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags())
                .extracting(tag -> tag.getKey())
                .allMatch(Set.of(TAG_RESULT, TAG_TYPE, TAG_LEVEL)::contains);
        assertThat(registry.getMeters())
                .extracting(Meter::getId)
                .allMatch(id -> id.getTag("tenant") == null)
                .allMatch(id -> id.getTag("session") == null)
                .allMatch(id -> id.getTag("execution") == null)
                .allMatch(id -> id.getTag("content") == null);
    }

    @Test
    void resetsLevelsThatDisappearFromTheLatestManifest() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LsmContextMetrics metrics = new LsmContextMetrics(registry);
        metrics.recordActiveSegments(SESSION_A, Map.of(0, 3, 1, 2));

        metrics.recordActiveSegments(SESSION_A, Map.of(1, 1));

        assertThat(
                        registry.get("agent_context_active_segments")
                                .tag(TAG_LEVEL, "0")
                                .gauge()
                                .value())
                .isZero();
        assertThat(
                        registry.get("agent_context_active_segments")
                                .tag(TAG_LEVEL, "1")
                                .gauge()
                                .value())
                .isEqualTo(1d);
    }

    @Test
    void aggregatesSessionContributionsAndRemovesOnlyTheUpdatedSessionsOldLevels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LsmContextMetrics metrics = new LsmContextMetrics(registry);
        metrics.recordActiveSegments(SESSION_A, Map.of(0, 2, 1, 1));
        metrics.recordActiveSegments(SESSION_B, Map.of(0, 3, 2, 1));

        metrics.recordActiveSegments(SESSION_A, Map.of(2, 2));

        assertThat(
                        registry.get("agent_context_active_segments")
                                .tag(TAG_LEVEL, "0")
                                .gauge()
                                .value())
                .isEqualTo(3d);
        assertThat(
                        registry.get("agent_context_active_segments")
                                .tag(TAG_LEVEL, "1")
                                .gauge()
                                .value())
                .isZero();
        assertThat(
                        registry.get("agent_context_active_segments")
                                .tag(TAG_LEVEL, "2")
                                .gauge()
                                .value())
                .isEqualTo(3d);
    }

    @Test
    void boundsTrackedSessionsAndRemovesEvictedContributionFromServiceGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LsmContextMetrics metrics =
                new LsmContextMetrics(registry, 2, Duration.ofHours(1), Clock.systemUTC());
        metrics.recordActiveSegments(SESSION_A, Map.of(0, 2));
        metrics.recordActiveSegments(SESSION_B, Map.of(0, 3));

        metrics.recordActiveSegments(SESSION_C, Map.of(0, 4));

        assertThat(metrics.trackedSessionCount()).isEqualTo(2);
        assertThat(
                        registry.get("agent_context_active_segments")
                                .tag(TAG_LEVEL, "0")
                                .gauge()
                                .value())
                .isEqualTo(7d);
    }

    @Test
    void expiresStaleSessionContributionsBeforeUpdatingServiceGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MutableClock clock = new MutableClock(Instant.parse("2026-08-13T00:00:00Z"));
        LsmContextMetrics metrics =
                new LsmContextMetrics(registry, 10, Duration.ofMinutes(5), clock);
        metrics.recordActiveSegments(SESSION_A, Map.of(0, 2));
        clock.advance(Duration.ofMinutes(6));

        metrics.recordActiveSegments(SESSION_B, Map.of(0, 3));

        assertThat(metrics.trackedSessionCount()).isEqualTo(1);
        assertThat(
                        registry.get("agent_context_active_segments")
                                .tag(TAG_LEVEL, "0")
                                .gauge()
                                .value())
                .isEqualTo(3d);
    }

    @Test
    void explicitCleanupRemovesOnlyTheTargetSessionContribution() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LsmContextMetrics metrics = new LsmContextMetrics(registry);
        metrics.recordActiveSegments(SESSION_A, Map.of(0, 2, 1, 1));
        metrics.recordActiveSegments(SESSION_B, Map.of(0, 3));

        metrics.cleanupActiveSegments(SESSION_A);

        assertThat(metrics.trackedSessionCount()).isEqualTo(1);
        assertThat(
                        registry.get("agent_context_active_segments")
                                .tag(TAG_LEVEL, "0")
                                .gauge()
                                .value())
                .isEqualTo(3d);
        assertThat(
                        registry.get("agent_context_active_segments")
                                .tag(TAG_LEVEL, "1")
                                .gauge()
                                .value())
                .isZero();
    }

    @Test
    void rejectsLevelsOutsideTheServiceBoundBeforeRegisteringMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LsmContextMetrics metrics = new LsmContextMetrics(registry);

        assertThatThrownBy(() -> metrics.recordActiveSegments(SESSION_A, Map.of(-1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metrics.recordActiveSegments(SESSION_A, Map.of(9, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metrics.recordSummary(-1, 100, 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metrics.recordSummary(9, 100, 50))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(registry.find("agent_context_active_segments").meters()).isEmpty();
        assertThat(registry.find("agent_context_compaction_input_tokens").meters()).isEmpty();
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
