package com.datagent.agentv2.conversation.runtime;

import com.datagent.agentv2.conversation.stream.ExecutionStreamCheckpointCoordinator;
import com.datagent.agentv2.platform.context.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public final class ExecutionOwnerRegistry {

    private static final String METRIC_OWNER_REGISTRY_SIZE =
            "data_agent.runtime.owner_registry.size";

    private final ConcurrentMap<String, Ownership> owners = new ConcurrentHashMap<>();
    private final ExecutionStreamCheckpointCoordinator checkpoints;

    public ExecutionOwnerRegistry(
            ExecutionWorkerCapacity capacity,
            MeterRegistry meters,
            ExecutionStreamCheckpointCoordinator checkpoints) {
        this.checkpoints = checkpoints;
        meters.gauge(METRIC_OWNER_REGISTRY_SIZE, this, ExecutionOwnerRegistry::size);
    }

    public boolean register(
            AgentExecutionPort.ClaimedAgentExecution run, ExecutionWorkerCapacity.Permit permit) {
        Ownership previous = owners.putIfAbsent(run.executionId(), new Ownership(run, permit));
        if (previous != null) {
            permit.close();
            return false;
        }
        return true;
    }

    public void release(String executionId) {
        Ownership ownership = owners.remove(executionId);
        if (ownership != null) {
            try {
                TenantContext.run(
                        ownership.execution().tenantId(),
                        () ->
                                checkpoints.flushExecutionAndRelease(
                                        ownership.execution(), Instant.now()));
            } catch (ExecutionOwnershipLostException ignored) {
                // Lease 已被接管或执行已终态化时，新的 owner/终态投影是权威结果。
            } finally {
                ownership.permit().close();
            }
        }
    }

    public boolean owns(String executionId) {
        return owners.containsKey(executionId);
    }

    public int size() {
        return owners.size();
    }

    public List<AgentExecutionPort.ClaimedAgentExecution> ownedExecutions() {
        return owners.values().stream().map(Ownership::execution).toList();
    }

    @PreDestroy
    public void close() {
        owners.keySet().forEach(this::release);
    }

    /** 绑定本机正在执行的任务与其占用的容量许可。 */
    private record Ownership(
            AgentExecutionPort.ClaimedAgentExecution execution,
            ExecutionWorkerCapacity.Permit permit) {}
}
