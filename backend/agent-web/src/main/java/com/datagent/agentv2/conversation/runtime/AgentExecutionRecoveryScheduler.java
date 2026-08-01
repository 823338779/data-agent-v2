package com.datagent.agentv2.conversation.runtime;

import static com.datagent.agentv2.conversation.AgentExecutionConstants.ERROR_EXECUTION_ALREADY_OWNED_PREFIX;

import com.datagent.agentv2.persistence.service.TenantService;
import com.datagent.agentv2.platform.context.TenantContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class AgentExecutionRecoveryScheduler {

    private final AgentExecutionLeaseService leases;
    private final ExecutionWorkerCapacity capacity;
    private final ExecutionOwnerRegistry owners;
    private final AgentExecutionPort execution;
    private final TenantService tenants;
    private final AgentExecutionRuntimeProperties properties;
    private final ExecutionWorkerIdentity identity;

    public AgentExecutionRecoveryScheduler(
            AgentExecutionLeaseService leases,
            ExecutionWorkerCapacity capacity,
            ExecutionOwnerRegistry owners,
            AgentExecutionPort execution,
            TenantService tenants,
            AgentExecutionRuntimeProperties properties,
            ExecutionWorkerIdentity identity) {
        this.leases = leases;
        this.capacity = capacity;
        this.owners = owners;
        this.execution = execution;
        this.tenants = tenants;
        this.properties = properties;
        this.identity = identity;
    }

    public boolean recoverNext() {
        ExecutionWorkerCapacity.Permit permit = capacity.tryAcquire().orElse(null);
        if (permit == null) {
            return false;
        }
        AgentExecutionLeaseService.RecoveryResult result;
        try {
            result = leases.takeoverNext(identity.workerInstanceId());
        } catch (RuntimeException failure) {
            permit.close();
            throw failure;
        }
        if (result.execution() == null) {
            permit.close();
            return false;
        }
        AgentExecutionPort.ClaimedAgentExecution run = result.execution();
        if (!owners.register(run, permit)) {
            throw new IllegalStateException(
                    ERROR_EXECUTION_ALREADY_OWNED_PREFIX + run.executionId());
        }
        try {
            execution.start(run, () -> owners.release(run.executionId()));
        } catch (RuntimeException failure) {
            owners.release(run.executionId());
            throw failure;
        }
        return true;
    }

    @Scheduled(fixedDelayString = "${data-agent.runtime.heartbeat-interval:15s}")
    public void heartbeatClaimedAgentExecutions() {
        if (!properties.schedulingEnabled()) {
            return;
        }
        for (AgentExecutionPort.ClaimedAgentExecution run : owners.ownedExecutions()) {
            boolean renewed = TenantContext.call(run.tenantId(), () -> leases.heartbeat(run));
            if (!renewed) {
                owners.release(run.executionId());
            }
        }
    }

    @Scheduled(fixedDelayString = "${data-agent.runtime.control-poll-interval:1s}")
    public void pollExecutionCancellations() {
        if (!properties.schedulingEnabled()) {
            return;
        }
        pollExecutionCancellationsNow();
    }

    public void pollExecutionCancellationsNow() {
        for (AgentExecutionPort.ClaimedAgentExecution run : owners.ownedExecutions()) {
            boolean cancelled =
                    TenantContext.call(run.tenantId(), () -> leases.cancelIfRequested(run));
            if (cancelled) {
                try {
                    execution.cancel(run);
                } finally {
                    owners.release(run.executionId());
                }
            }
        }
    }

    @Scheduled(fixedDelayString = "${data-agent.runtime.queue-scan-interval:1s}")
    public void recoverExpiredExecutions() {
        if (!properties.schedulingEnabled()) {
            return;
        }
        for (String tenantId : tenants.activeTenantIds()) {
            while (capacity.available() > 0 && TenantContext.call(tenantId, this::recoverNext)) {
                // Continue until this tenant has no expired executions or the instance is full.
            }
            if (capacity.available() == 0) {
                return;
            }
        }
    }
}
