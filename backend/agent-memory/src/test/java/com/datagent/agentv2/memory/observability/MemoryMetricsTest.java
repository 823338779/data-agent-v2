package com.datagent.agentv2.memory.observability;

import static org.assertj.core.api.Assertions.*;

import com.datagent.agentv2.memory.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MemoryMetricsTest {
    @Test
    void memoryMetricLabelsNeverContainPersonalContent() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MemoryMetrics(registry);
        metrics.recordOperation(MemorySource.USER_UI, MemoryStatus.CREATED);
        metrics.recordInjection(4, 2, 37);
        metrics.recordReview("BLOCKED", Duration.ofSeconds(3));
        metrics.recordReadFailure();
        metrics.recordQueueWait(Duration.ofSeconds(7));
        metrics.recordQuota(Map.of(MemoryKind.PERSON_PROFILE, new MemoryQuota(17, 800)));
        assertThat(registry.get("memory.operations").counter().count()).isEqualTo(1);
        assertThat(registry.get("memory.injection.tokens").summary().totalAmount()).isEqualTo(37);
        assertThat(registry.get("memory.review").timer().count()).isEqualTo(1);
        assertThat(
                        registry.get("memory.queue.wait")
                                .timer()
                                .totalTime(java.util.concurrent.TimeUnit.SECONDS))
                .isEqualTo(7);
        assertThat(registry.get("memory.quota.used").gauge().value()).isEqualTo(17);
        assertThat(registry.getMeters())
                .allSatisfy(
                        meter ->
                                assertThat(meter.getId().getTags())
                                        .allSatisfy(
                                                tag -> {
                                                    assertThat(tag.getKey())
                                                            .isIn("source", "status", "kind");
                                                    assertThat(tag.getValue())
                                                            .isIn(
                                                                    "USER_UI",
                                                                    "CREATED",
                                                                    "BLOCKED",
                                                                    "person_profile");
                                                }));
        assertThatThrownBy(() -> metrics.recordReview("个人正文", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
