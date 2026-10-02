package com.datagent.agentv2.memory;

import com.datagent.agentv2.memory.config.MemoryProperties;
import com.datagent.agentv2.memory.review.MemoryReviewCoordinator;
import com.datagent.agentv2.memory.review.MemoryReviewWorker;
import java.util.UUID;
import java.util.concurrent.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** ready 后扫描可靠队列；有界执行器的槽位由真实退出归还，忽略中断的 provider 不会触发无限替代线程。 */
@Component
@RequiredArgsConstructor
@Slf4j
public final class MemoryReviewScheduler implements DisposableBean {
    private final MemoryReviewCoordinator coordinator;
    private final MemoryReviewWorker worker;
    private final MemoryProperties properties;
    private final ThreadPoolExecutor memoryReviewExecutor;
    private final ScheduledExecutorService memoryReviewScanExecutor;
    private final String owner = "memory-review-" + UUID.randomUUID();
    private volatile boolean ready;
    private ScheduledFuture<?> scan;

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void ready() {
        if (ready) {
            return;
        }
        ready = true;
        scan =
                memoryReviewScanExecutor.scheduleWithFixedDelay(
                        this::scan,
                        properties.getScanInitialDelay().toMillis(),
                        properties.getScanInterval().toMillis(),
                        TimeUnit.MILLISECONDS);
    }

    public void scan() {
        if (!ready) {
            return;
        }
        int slots =
                memoryReviewExecutor.getMaximumPoolSize() - memoryReviewExecutor.getActiveCount();
        if (slots <= 0) {
            return;
        }
        try {
            int limit = Math.min(Math.min(4, slots), properties.getClaimBatchSize());
            coordinator.reactivateBlocked(worker.configurationFingerprint(), limit);
            for (var claim : coordinator.claimDue(owner, limit)) {
                try {
                    memoryReviewExecutor.execute(() -> worker.run(claim));
                } catch (RejectedExecutionException full) {
                    coordinator.release(claim);
                }
            }
        } catch (RuntimeException failure) {
            // 不输出 provider 或数据库异常正文，且一次扫描失败不能让后续周期永久停止。
            log.warn(
                    "后台记忆队列扫描暂时失败",
                    com.datagent.agentv2.diagnostics.SafeFailures.sanitized(failure));
        }
    }

    @Override
    public synchronized void destroy() {
        ready = false;
        if (scan != null) {
            scan.cancel(false);
        }
    }
}
