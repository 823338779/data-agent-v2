package com.datagent.agentv2.conversation.runtime;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public final class InstanceRunCapacity {

    private static final String METRIC_CAPACITY_MAX = "data_agent.runtime.capacity.max";
    private static final String METRIC_CAPACITY_USED = "data_agent.runtime.capacity.used";
    private static final String METRIC_CAPACITY_AVAILABLE = "data_agent.runtime.capacity.available";

    private final int maximum;
    private final Semaphore permits;

    public InstanceRunCapacity(RunRuntimeProperties properties, MeterRegistry meters) {
        this.maximum = properties.maxRunningRunsPerInstance();
        this.permits = new Semaphore(maximum, true);
        meters.gauge(METRIC_CAPACITY_MAX, this, InstanceRunCapacity::maximum);
        meters.gauge(METRIC_CAPACITY_USED, this, InstanceRunCapacity::used);
        meters.gauge(METRIC_CAPACITY_AVAILABLE, this, InstanceRunCapacity::available);
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
