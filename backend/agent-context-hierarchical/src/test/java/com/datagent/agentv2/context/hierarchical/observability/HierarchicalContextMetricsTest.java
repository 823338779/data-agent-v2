package com.datagent.agentv2.context.hierarchical.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.conversation.context.ContextBudget;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HierarchicalContextMetricsTest {

    private static final String TAG_STRATEGY = "strategy";
    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_LEVEL = "level";
    private static final Set<String> APPROVED_TAGS = Set.of(TAG_STRATEGY, TAG_OUTCOME, TAG_LEVEL);

    @Test
    void recordsRequiredMeasurementsWithApprovedLowCardinalityTagsOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HierarchicalContextMetrics metrics = new HierarchicalContextMetrics(registry);

        // One observation covers pressure, compaction, node, frontier and checkpoint meters.
        metrics.recordInput(75, new ContextBudget(100, 10, 5, 0));
        metrics.recordCompaction(
                HierarchicalContextMetrics.CompactionOutcome.SUCCEEDED, Duration.ofMillis(250));
        metrics.recordGeneratedNodes(2, 3);
        metrics.recordActiveFrontier(Map.of(0, 2, 2, 1));
        metrics.recordCheckpoint(80L, 4L);

        assertThat(registry.get("agent_context_input_estimated_tokens").gauge().value())
                .isEqualTo(75d);
        assertThat(registry.get("agent_context_utilization_ratio").gauge().value())
                .isEqualTo(0.75d);
        assertThat(
                        registry.get("agent_context_compaction_total")
                                .tag(TAG_OUTCOME, "succeeded")
                                .counter()
                                .count())
                .isEqualTo(1d);
        assertThat(
                        registry.get("agent_context_compaction_duration_seconds")
                                .tag(TAG_OUTCOME, "succeeded")
                                .timer()
                                .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(250d);
        assertThat(
                        registry.get("agent_context_generated_nodes_total")
                                .tag(TAG_LEVEL, "2")
                                .counter()
                                .count())
                .isEqualTo(3d);
        assertThat(
                        registry.get("agent_context_active_frontier_nodes")
                                .tag(TAG_LEVEL, "0")
                                .gauge()
                                .value())
                .isEqualTo(2d);
        assertThat(registry.get("agent_context_cold_pointer").gauge().value()).isEqualTo(80d);
        assertThat(registry.get("agent_context_strategy_generation").gauge().value()).isEqualTo(4d);

        // Meter identities never contain high-cardinality identifiers or business content.
        assertThat(registry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags())
                .extracting(tag -> tag.getKey())
                .allMatch(APPROVED_TAGS::contains);
        assertThat(registry.getMeters())
                .extracting(Meter::getId)
                .allMatch(id -> "hierarchical".equals(id.getTag(TAG_STRATEGY)))
                .allMatch(id -> id.getTag("tenant") == null)
                .allMatch(id -> id.getTag("session") == null)
                .allMatch(id -> id.getTag("message") == null)
                .allMatch(id -> id.getTag("summary") == null)
                .allMatch(id -> id.getTag("tool") == null)
                .allMatch(id -> id.getTag("content") == null);
    }

    @Test
    void clearsFrontierLevelsMissingFromTheLatestPublishedView() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HierarchicalContextMetrics metrics = new HierarchicalContextMetrics(registry);
        metrics.recordActiveFrontier(Map.of(0, 3, 1, 2));

        metrics.recordActiveFrontier(Map.of(1, 1));

        assertThat(
                        registry.get("agent_context_active_frontier_nodes")
                                .tag(TAG_LEVEL, "0")
                                .gauge()
                                .value())
                .isZero();
        assertThat(
                        registry.get("agent_context_active_frontier_nodes")
                                .tag(TAG_LEVEL, "1")
                                .gauge()
                                .value())
                .isEqualTo(1d);
    }

    @Test
    void bucketsHigherHierarchyLevelsWithoutIncreasingTagCardinality() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HierarchicalContextMetrics metrics = new HierarchicalContextMetrics(registry);

        // Domain-valid high levels share one bounded overflow bucket for both meter families.
        for (int level = 0; level <= 8; level++) {
            metrics.recordGeneratedNodes(level, 1);
        }
        metrics.recordGeneratedNodes(9, 1);
        metrics.recordGeneratedNodes(62, 2);
        metrics.recordActiveFrontier(Map.of(9, 3, 62, 4));

        assertThat(
                        registry.get("agent_context_generated_nodes_total")
                                .tag(TAG_LEVEL, "9_plus")
                                .counter()
                                .count())
                .isEqualTo(3d);
        assertThat(
                        registry.get("agent_context_active_frontier_nodes")
                                .tag(TAG_LEVEL, "9_plus")
                                .gauge()
                                .value())
                .isEqualTo(7d);
        assertThat(levelTagValues(registry))
                .containsOnly("0", "1", "2", "3", "4", "5", "6", "7", "8", "9_plus")
                .hasSize(10);
    }

    @Test
    void rejectsInvalidMetricDimensionsBeforeRegisteringDynamicMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HierarchicalContextMetrics metrics = new HierarchicalContextMetrics(registry);

        assertThatThrownBy(() -> metrics.recordGeneratedNodes(-1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metrics.recordGeneratedNodes(0, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metrics.recordActiveFrontier(Map.of(-1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metrics.recordActiveFrontier(Map.of(0, -1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metrics.recordCheckpoint(-1L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                metrics.recordCompaction(
                                        HierarchicalContextMetrics.CompactionOutcome.FAILED,
                                        Duration.ofMillis(-1L)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(registry.find("agent_context_generated_nodes_total").meters()).isEmpty();
        assertThat(registry.find("agent_context_active_frontier_nodes").meters()).isEmpty();
    }

    private static Set<String> levelTagValues(SimpleMeterRegistry registry) {
        return registry.getMeters().stream()
                .map(Meter::getId)
                .map(id -> id.getTag(TAG_LEVEL))
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
    }
}
