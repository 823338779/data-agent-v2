package com.datagent.agentv2.conversation.runtime;

import com.datagent.agentv2.persistence.service.TenantService;
import com.datagent.agentv2.platform.context.TenantContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "data-agent.runtime.scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class AgentExecutionQueueScheduler {

    private final TenantService tenants;
    private final AgentExecutionClaimService claims;
    private final ExecutionWorkerCapacity capacity;

    public AgentExecutionQueueScheduler(
            TenantService tenants,
            AgentExecutionClaimService claims,
            ExecutionWorkerCapacity capacity) {
        this.tenants = tenants;
        this.claims = claims;
        this.capacity = capacity;
    }

    @Scheduled(fixedDelayString = "${data-agent.runtime.queue-scan-interval:1s}")
    public void claimQueuedExecutions() {
        for (String tenantId : tenants.activeTenantIds()) {
            while (capacity.available() > 0 && TenantContext.call(tenantId, claims::claimNext)) {
                // Continue until this tenant has no claimable work or the instance is full.
            }
            if (capacity.available() == 0) {
                return;
            }
        }
    }
}
