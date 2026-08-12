package com.datagent.agentv2.context.lsm.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan.OperationType;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LsmContextMetricsTest {

    private static final String TAG_LEVEL = "level";
    private static final String TAG_RESULT = "result";
    private static final String TAG_TYPE = "type";
    private static final String RESULT_PUBLISHED = "published";
    private static final String TYPE_SEAL_L0 = "seal_l0";

    @Test
    void recordsApprovedMeasurementsWithLowCardinalityTagsOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LsmContextMetrics metrics = new LsmContextMetrics(registry);

        // 一次完整观测覆盖输入压力、活动层级、压缩结果、候选压缩率和冲突计数。
        metrics.recordInput(48_000, 0.75d);
        metrics.recordActiveSegments(Map.of(0, 4, 2, 1));
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
        metrics.recordActiveSegments(Map.of(0, 3, 1, 2));

        metrics.recordActiveSegments(Map.of(1, 1));

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
}
