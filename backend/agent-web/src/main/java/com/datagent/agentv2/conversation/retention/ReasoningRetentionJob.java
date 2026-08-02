package com.datagent.agentv2.conversation.retention;

import com.datagent.agentv2.persistence.service.AgentExecutionStreamCheckpointService;
import com.datagent.agentv2.persistence.service.ModelInvocationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 分页、幂等地清理已到期终态 reasoning，并只记录计数和耗时。 */
@Component
@Slf4j
public final class ReasoningRetentionJob {

    private static final String METRIC_SCANNED = "data_agent.reasoning_retention.scanned";
    private static final String METRIC_CLEANED = "data_agent.reasoning_retention.cleaned";
    private static final String METRIC_FAILED = "data_agent.reasoning_retention.failed";
    private static final String METRIC_DURATION = "data_agent.reasoning_retention.duration";
    private static final String LOG_COMPLETED =
            "Reasoning retention completed: scanned={}, cleaned={}";
    private static final String LOG_FAILED = "Reasoning retention failed after cleaning {} rows";

    private final AgentExecutionStreamCheckpointService streams;
    private final ModelInvocationService invocations;
    private final ReasoningRetentionProperties properties;
    private final Counter scanned;
    private final Counter cleaned;
    private final Counter failed;
    private final Timer duration;

    public ReasoningRetentionJob(
            AgentExecutionStreamCheckpointService streams,
            ModelInvocationService invocations,
            ReasoningRetentionProperties properties,
            MeterRegistry meters) {
        this.streams = streams;
        this.invocations = invocations;
        this.properties = properties;
        this.scanned = meters.counter(METRIC_SCANNED);
        this.cleaned = meters.counter(METRIC_CLEANED);
        this.failed = meters.counter(METRIC_FAILED);
        this.duration = meters.timer(METRIC_DURATION);
    }

    @Scheduled(fixedDelayString = "${data-agent.reasoning-retention.scan-interval:1h}")
    public void scheduledRun() {
        if (properties.enabled()) {
            runOnce(Instant.now());
        }
    }

    public void runOnce(Instant now) {
        long started = System.nanoTime();
        int cleanedCount = 0;
        try {
            // 两张表各自使用短事务 SKIP LOCKED 分页，多实例可并发消费不同页面。
            cleanedCount += drain(() -> streams.clearExpiredReasoning(now, properties.batchSize()));
            cleanedCount +=
                    drain(() -> invocations.clearExpiredReasoning(now, properties.batchSize()));
            scanned.increment(cleanedCount);
            cleaned.increment(cleanedCount);
            log.info(LOG_COMPLETED, cleanedCount, cleanedCount);
        } catch (RuntimeException failure) {
            failed.increment();
            log.warn(LOG_FAILED, cleanedCount, failure);
            throw failure;
        } finally {
            duration.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private int drain(IntSupplier page) {
        int total = 0;
        for (int batch = 0; batch < properties.maxBatches(); batch++) {
            int changed = page.getAsInt();
            total += changed;
            if (changed < properties.batchSize()) {
                break;
            }
        }
        return total;
    }
}
