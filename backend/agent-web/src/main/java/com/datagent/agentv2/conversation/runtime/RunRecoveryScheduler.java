package com.datagent.agentv2.conversation.runtime;

import static com.datagent.agentv2.conversation.RunConstants.ERROR_RUN_ALREADY_OWNED_PREFIX;

import com.datagent.agentv2.persistence.service.TenantService;
import com.datagent.agentv2.platform.context.TenantContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class RunRecoveryScheduler {

    private final RunLeaseService leases;
    private final InstanceRunCapacity capacity;
    private final RunOwnerRegistry owners;
    private final RunExecutionPort execution;
    private final TenantService tenants;
    private final RunRuntimeProperties properties;
    private final RunInstanceIdentity identity;

    public RunRecoveryScheduler(
            RunLeaseService leases,
            InstanceRunCapacity capacity,
            RunOwnerRegistry owners,
            RunExecutionPort execution,
            TenantService tenants,
            RunRuntimeProperties properties,
            RunInstanceIdentity identity) {
        this.leases = leases;
        this.capacity = capacity;
        this.owners = owners;
        this.execution = execution;
        this.tenants = tenants;
        this.properties = properties;
        this.identity = identity;
    }

    public boolean recoverNext() {
        InstanceRunCapacity.Permit permit = capacity.tryAcquire().orElse(null);
        if (permit == null) {
            return false;
        }
        RunLeaseService.RecoveryResult result;
        try {
            result = leases.takeoverNext(identity.id());
        } catch (RuntimeException failure) {
            permit.close();
            throw failure;
        }
        if (result.run() == null) {
            permit.close();
            return false;
        }
        RunExecutionPort.OwnedRun run = result.run();
        if (!owners.register(run, permit)) {
            throw new IllegalStateException(ERROR_RUN_ALREADY_OWNED_PREFIX + run.runId());
        }
        try {
            execution.start(run, () -> owners.release(run.runId()));
        } catch (RuntimeException failure) {
            owners.release(run.runId());
            throw failure;
        }
        return true;
    }

    @Scheduled(fixedDelayString = "${data-agent.runtime.heartbeat-interval:15s}")
    public void heartbeatOwnedRuns() {
        if (!properties.schedulingEnabled()) {
            return;
        }
        for (RunExecutionPort.OwnedRun run : owners.ownedRuns()) {
            boolean renewed = TenantContext.call(run.tenantId(), () -> leases.heartbeat(run));
            if (!renewed) {
                owners.release(run.runId());
            }
        }
    }

    @Scheduled(fixedDelayString = "${data-agent.runtime.control-poll-interval:1s}")
    public void pollRunControls() {
        if (!properties.schedulingEnabled()) {
            return;
        }
        pollRunControlsNow();
    }

    public void pollRunControlsNow() {
        for (RunExecutionPort.OwnedRun run : owners.ownedRuns()) {
            boolean cancelled =
                    TenantContext.call(run.tenantId(), () -> leases.cancelIfRequested(run));
            if (cancelled) {
                try {
                    execution.cancel(run);
                } finally {
                    owners.release(run.runId());
                }
            }
        }
    }

    @Scheduled(fixedDelayString = "${data-agent.runtime.queue-scan-interval:1s}")
    public void recoverExpiredRuns() {
        if (!properties.schedulingEnabled()) {
            return;
        }
        for (String tenantId : tenants.activeTenantIds()) {
            while (capacity.available() > 0 && TenantContext.call(tenantId, this::recoverNext)) {
                // Continue until this tenant has no expired runs or the instance is full.
            }
            if (capacity.available() == 0) {
                return;
            }
        }
    }
}
