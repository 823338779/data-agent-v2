package com.datagent.agentv2.context.lsm.observability;

import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan.OperationType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 统一注册 LSM 上下文指标，并将动态标签限制为批准的低基数枚举与层级。 */
public final class LsmContextMetrics {

    private static final String INPUT_ESTIMATED_TOKENS = "agent_context_input_estimated_tokens";
    private static final String UTILIZATION_RATIO = "agent_context_utilization_ratio";
    private static final String ACTIVE_SEGMENTS = "agent_context_active_segments";
    private static final String COMPACTION_TOTAL = "agent_context_compaction_total";
    private static final String COMPACTION_DURATION = "agent_context_compaction_duration_seconds";
    private static final String COMPACTION_INPUT_TOKENS = "agent_context_compaction_input_tokens";
    private static final String COMPACTION_OUTPUT_TOKENS = "agent_context_compaction_output_tokens";
    private static final String COMPRESSION_RATIO = "agent_context_compression_ratio";
    private static final String ESTIMATION_ERROR_RATIO = "agent_context_estimation_error_ratio";
    private static final String MANIFEST_CAS_CONFLICTS =
            "agent_context_manifest_cas_conflicts_total";
    private static final String WINDOW_EXCEEDED = "agent_context_window_exceeded_total";
    private static final String TAG_LEVEL = "level";
    private static final String TAG_RESULT = "result";
    private static final String TAG_TYPE = "type";

    /** 最近一次估算输入 token 数；不按会话或 execution 拆分。 */
    private final AtomicLong inputEstimatedTokens = new AtomicLong();

    /** 最近一次模型窗口利用率；不携带业务正文或标识符。 */
    private final AtomicReference<Double> utilizationRatio = new AtomicReference<>(0d);

    /** 最近一次估算误差比率。 */
    private final AtomicReference<Double> estimationErrorRatio = new AtomicReference<>(0d);

    /** 每个有限层级的当前活动节点数。 */
    private final ConcurrentMap<Integer, AtomicLong> activeSegments = new ConcurrentHashMap<>();

    /** 指标注册表，仅用于有限标签组合的按需注册。 */
    private final MeterRegistry registry;

    /** 无标签的 manifest CAS 冲突计数器。 */
    private final Counter manifestCasConflicts;

    /** 无标签的上下文硬窗口溢出计数器。 */
    private final Counter windowExceeded;

    public LsmContextMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");

        // 全局 gauges 只保留最新服务观测值，不绑定任何租户或对话标识。
        registry.gauge(INPUT_ESTIMATED_TOKENS, inputEstimatedTokens);
        registry.gauge(UTILIZATION_RATIO, utilizationRatio, AtomicReference::get);
        registry.gauge(ESTIMATION_ERROR_RATIO, estimationErrorRatio, AtomicReference::get);
        manifestCasConflicts = registry.counter(MANIFEST_CAS_CONFLICTS);
        windowExceeded = registry.counter(WINDOW_EXCEEDED);
    }

    /** 为不参与运行时装配的单元测试和直接构造调用提供隔离的无导出注册表。 */
    public static LsmContextMetrics isolated() {
        return new LsmContextMetrics(new SimpleMeterRegistry());
    }

    /** 记录最新输入估算和窗口利用率。 */
    public void recordInput(long estimatedTokens, double utilization) {
        inputEstimatedTokens.set(Math.max(0L, estimatedTokens));
        utilizationRatio.set(nonNegativeFinite(utilization));
    }

    /** 更新 manifest 各层活动节点数，并把本次消失的旧层级归零。 */
    public void recordActiveSegments(Map<Integer, Integer> countsByLevel) {
        Map<Integer, Integer> counts = Map.copyOf(countsByLevel);
        Set<Integer> missingLevels = new HashSet<>(activeSegments.keySet());
        missingLevels.removeAll(counts.keySet());
        missingLevels.forEach(level -> activeSegments.get(level).set(0L));
        counts.forEach(
                (level, count) -> {
                    if (level < 0 || count < 0) {
                        throw new IllegalArgumentException(
                                "metric level and active count must not be negative");
                    }
                    activeSegments
                            .computeIfAbsent(level, this::registerActiveSegmentsGauge)
                            .set(count);
                });
    }

    /** 记录一次有限结果、有限操作类型及总耗时。 */
    public void recordCompaction(CompactionResult result, OperationType type, Duration duration) {
        String resultTag = Objects.requireNonNull(result, "result").tagValue();
        String typeTag = operationTag(type);
        registry.counter(COMPACTION_TOTAL, TAG_RESULT, resultTag, TAG_TYPE, typeTag).increment();
        Timer.builder(COMPACTION_DURATION)
                .tag(TAG_TYPE, typeTag)
                .register(registry)
                .record(nonNegative(duration));
    }

    /** 记录单个摘要层级的输入、输出 token 和压缩比。 */
    public void recordSummary(int level, long inputTokens, long outputTokens) {
        if (level < 0 || inputTokens < 0L || outputTokens < 0L) {
            throw new IllegalArgumentException("summary metric values must not be negative");
        }
        String levelTag = Integer.toString(level);
        summary(COMPACTION_INPUT_TOKENS, levelTag).record(inputTokens);
        summary(COMPACTION_OUTPUT_TOKENS, levelTag).record(outputTokens);
        double ratio = inputTokens == 0L ? 0d : (double) outputTokens / inputTokens;
        summary(COMPRESSION_RATIO, levelTag).record(ratio);
    }

    /** 更新最新估算误差比率。 */
    public void recordEstimationError(double ratio) {
        estimationErrorRatio.set(nonNegativeFinite(ratio));
    }

    public void recordManifestCasConflict() {
        manifestCasConflicts.increment();
    }

    public void recordWindowExceeded() {
        windowExceeded.increment();
    }

    private AtomicLong registerActiveSegmentsGauge(int level) {
        AtomicLong value = new AtomicLong();
        registry.gauge(
                ACTIVE_SEGMENTS,
                java.util.List.of(
                        io.micrometer.core.instrument.Tag.of(TAG_LEVEL, Integer.toString(level))),
                value);
        return value;
    }

    private DistributionSummary summary(String name, String level) {
        return DistributionSummary.builder(name).tag(TAG_LEVEL, level).register(registry);
    }

    private static Duration nonNegative(Duration duration) {
        Duration value = Objects.requireNonNull(duration, "duration");
        if (value.isNegative()) {
            throw new IllegalArgumentException("metric duration must not be negative");
        }
        return value;
    }

    private static double nonNegativeFinite(double value) {
        return Double.isFinite(value) && value >= 0d ? value : 0d;
    }

    private static String operationTag(OperationType type) {
        return Objects.requireNonNull(type, "type").name().toLowerCase(Locale.ROOT);
    }

    /** 压缩协调器可产生的有限低基数结果。 */
    public enum CompactionResult {
        PUBLISHED,
        SUPERSEDED,
        CANCELLED,
        UNCHANGED,
        FAILED;

        String tagValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
