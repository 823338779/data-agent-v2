package com.datagent.agentv2.conversation.runtime;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public final class RunOwnerRegistry {

    private static final String METRIC_OWNER_REGISTRY_SIZE =
            "data_agent.runtime.owner_registry.size";

    private final ConcurrentMap<String, Ownership> owners = new ConcurrentHashMap<>();

    public RunOwnerRegistry(InstanceRunCapacity capacity, MeterRegistry meters) {
        meters.gauge(METRIC_OWNER_REGISTRY_SIZE, this, RunOwnerRegistry::size);
    }

    public boolean register(RunExecutionPort.OwnedRun run, InstanceRunCapacity.Permit permit) {
        Ownership previous = owners.putIfAbsent(run.runId(), new Ownership(run, permit));
        if (previous != null) {
            permit.close();
            return false;
        }
        return true;
    }

    public void release(String runId) {
        Ownership ownership = owners.remove(runId);
        if (ownership != null) {
            ownership.permit().close();
        }
    }

    public boolean owns(String runId) {
        return owners.containsKey(runId);
    }

    public int size() {
        return owners.size();
    }

    public List<RunExecutionPort.OwnedRun> ownedRuns() {
        return owners.values().stream().map(Ownership::run).toList();
    }

    @PreDestroy
    public void close() {
        owners.keySet().forEach(this::release);
    }

    private record Ownership(RunExecutionPort.OwnedRun run, InstanceRunCapacity.Permit permit) {}
}
