package com.datagent.agentv2.conversation.runtime;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public final class ExecutionWorkerCapacity {

    private static final String METRIC_CAPACITY_MAX = "data_agent.runtime.capacity.max";
    private static final String METRIC_CAPACITY_USED = "data_agent.runtime.capacity.used";
    private static final String METRIC_CAPACITY_AVAILABLE = "data_agent.runtime.capacity.available";

    private final int maximum;
    private final Semaphore permits;

    public ExecutionWorkerCapacity(
            AgentExecutionRuntimeProperties properties, MeterRegistry meters) {
        this.maximum = properties.maxConcurrentExecutionsPerInstance();
        this.permits = new Semaphore(maximum, true);
        meters.gauge(METRIC_CAPACITY_MAX, this, ExecutionWorkerCapacity::maximum);
        meters.gauge(METRIC_CAPACITY_USED, this, ExecutionWorkerCapacity::used);
        meters.gauge(METRIC_CAPACITY_AVAILABLE, this, ExecutionWorkerCapacity::available);
    }

    public Optional<Permit> tryAcquire() {
        return permits.tryAcquire() ? Optional.of(new Permit(permits)) : Optional.empty();
    }

    public int maximum() {
        return maximum;
    }

    public int used() {
        return maximum - permits.availablePermits();
    }

    public int available() {
        return permits.availablePermits();
    }

    public static final class Permit implements AutoCloseable {

        private final Semaphore permits;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(Semaphore permits) {
            this.permits = permits;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                permits.release();
            }
        }
    }
}
