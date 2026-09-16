package com.datagent.agentv2.memory.observability;

import com.datagent.agentv2.memory.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 个人记忆低基数指标；只接收分类、来源和受限状态，不接收正文或任何业务标识。 */
@Component
@RequiredArgsConstructor
public class MemoryMetrics {
    private static final Set<String> REVIEW_STATUSES =
            Set.of(
                    "PENDING",
                    "COMPLETED",
                    "CANCELLED",
                    "FAILED",
                    "BLOCKED",
                    "LEASE_LOST",
                    "UNAVAILABLE");
    private final MeterRegistry registry;
    private final Map<MemoryKind, AtomicInteger> used = new ConcurrentHashMap<>();
    private final Map<MemoryKind, AtomicInteger> limits = new ConcurrentHashMap<>();

    public void recordOperation(MemorySource source, MemoryStatus status) {
        registry.counter("memory.operations", "source", source.name(), "status", status.name())
                .increment();
    }

    /** 只在最终 prepare 后调用一次；tokens 含实际贡献包裹和协议，不含证据元数据及原会话。 */
    public void recordInjection(int candidates, int injected, int tokens) {
        registry.summary("memory.injection.candidates").record(candidates);
        registry.summary("memory.injection.entries").record(injected);
        registry.summary("memory.injection.tokens").record(tokens);
    }

    /** 状态为实际提交终态，失去提交资格单列 LEASE_LOST，依赖故障单列 UNAVAILABLE。 */
    public void recordReview(String status, Duration duration) {
        if (!REVIEW_STATUSES.contains(status)) throw new IllegalArgumentException("未知审查指标状态");
        registry.timer("memory.review", "status", status).record(duration);
    }

    public void recordReadFailure() {
        registry.counter("memory.read.failures").increment();
    }

    /** 起点为 availableAt：统计任务到期可领取之后的等待，不把配置延迟混入积压。 */
    public void recordQueueWait(Duration duration) {
        registry.timer("memory.queue.wait")
                .record(duration.isNegative() ? Duration.ZERO : duration);
    }

    public void recordQuota(Map<MemoryKind, MemoryQuota> quotas) {
        quotas.forEach(
                (kind, quota) -> {
                    gauge(used, "memory.quota.used", kind).set(quota.used());
                    gauge(limits, "memory.quota.limit", kind).set(quota.limit());
                });
    }

    private AtomicInteger gauge(
            Map<MemoryKind, AtomicInteger> values, String name, MemoryKind kind) {
        // 注册由 registry 保持弱引用，这两个小型枚举表保留 gauge 的强引用。
        var value = values.computeIfAbsent(kind, ignored -> new AtomicInteger());
        if (registry.find(name).tag("kind", kind.value()).gauge() == null)
            registry.gauge(
                    name,
                    java.util.List.of(io.micrometer.core.instrument.Tag.of("kind", kind.value())),
                    value);
        return value;
    }

    /** 不可在事务可能回滚时上报成功；同步回调只做内存指标，不再访问数据库。 */
    public void afterCommit(Runnable record) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            record.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        record.run();
                    }
                });
    }
}
