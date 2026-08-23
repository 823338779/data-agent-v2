package com.datagent.agentv2.context.hierarchical.observability;

import com.datagent.agentv2.conversation.context.ContextBudget;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 注册滚动 Cold 上下文指标，标签只使用固定策略与有限结果。 */
public final class HierarchicalContextMetrics {

    private static final String STRATEGY_ID = "hierarchical";
    private static final String INPUT_ESTIMATED_TOKENS = "agent_context_input_estimated_tokens";
    private static final String UTILIZATION_RATIO = "agent_context_utilization_ratio";
    private static final String COMPACTION_TOTAL = "agent_context_compaction_total";
    private static final String COMPACTION_DURATION = "agent_context_compaction_duration_seconds";
    private static final String COLD_POINTER = "agent_context_cold_pointer";
    private static final String STRATEGY_GENERATION = "agent_context_strategy_generation";
    private static final String TAG_STRATEGY = "strategy";
    private static final String TAG_OUTCOME = "outcome";
    private static final Tags STRATEGY_TAGS = Tags.of(TAG_STRATEGY, STRATEGY_ID);

    /** 最近一次完整模型输入的估算 token 数。 */
    private final AtomicLong inputEstimatedTokens = new AtomicLong();

    /** 最近一次完整模型输入占 context window 的比例。 */
    private final AtomicReference<Double> utilizationRatio = new AtomicReference<>(0d);

    /** 最近一次正式 checkpoint 的 Cold 消息指针。 */
    private final AtomicLong coldPointer = new AtomicLong();

    /** 最近一次正式 checkpoint 的策略代次。 */
    private final AtomicLong strategyGeneration = new AtomicLong();

    /** 仅用于注册固定名称和有限标签组合的 Micrometer registry。 */
    private final MeterRegistry registry;

    public HierarchicalContextMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");

        // 全局 gauge 不携带租户、会话、消息或正文，只暴露最近安全观测值。
        registry.gauge(INPUT_ESTIMATED_TOKENS, STRATEGY_TAGS, inputEstimatedTokens);
        registry.gauge(UTILIZATION_RATIO, STRATEGY_TAGS, utilizationRatio, AtomicReference::get);
        registry.gauge(COLD_POINTER, STRATEGY_TAGS, coldPointer);
        registry.gauge(STRATEGY_GENERATION, STRATEGY_TAGS, strategyGeneration);
    }

    /** 为不参与 Spring 运行时装配的直接构造调用提供隔离注册表。 */
    public static HierarchicalContextMetrics isolated() {
        return new HierarchicalContextMetrics(new SimpleMeterRegistry());
    }

    /** 记录完整输入 token 和相对于 context window 的利用率。 */
    public void recordInput(long estimatedTokens, ContextBudget budget) {
        if (estimatedTokens < 0L) {
            throw new IllegalArgumentException("estimatedTokens must not be negative");
        }
        ContextBudget checkedBudget = Objects.requireNonNull(budget, "budget");
        inputEstimatedTokens.set(estimatedTokens);
        utilizationRatio.set((double) estimatedTokens / checkedBudget.contextWindowTokens());
    }

    /** 按有限结果记录一次压缩尝试及其完整耗时。 */
    public void recordCompaction(CompactionOutcome outcome, Duration duration) {
        String outcomeTag = Objects.requireNonNull(outcome, "outcome").tagValue();
        Duration checkedDuration = Objects.requireNonNull(duration, "duration");
        if (checkedDuration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        List<Tag> tags = outcomeTags(outcomeTag);
        Counter.builder(COMPACTION_TOTAL).tags(tags).register(registry).increment();
        Timer.builder(COMPACTION_DURATION).tags(tags).register(registry).record(checkedDuration);
    }

    /** 更新最近一次正式发布的 Cold 指针和策略代次。 */
    public void recordCheckpoint(long summarizedUpToMessageSequence, long generation) {
        if (summarizedUpToMessageSequence < 0L || generation < 0L) {
            throw new IllegalArgumentException("checkpoint metrics must not be negative");
        }
        coldPointer.set(summarizedUpToMessageSequence);
        strategyGeneration.set(generation);
    }

    private static List<Tag> outcomeTags(String outcome) {
        return List.of(Tag.of(TAG_STRATEGY, STRATEGY_ID), Tag.of(TAG_OUTCOME, outcome));
    }

    /** 策略入口可观察到的有限压缩结果，不暴露作业或会话标识。 */
    public enum CompactionOutcome {
        SUCCEEDED,
        FAILED;

        private String tagValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
