package com.datagent.agentv2.conversation.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "data-agent.runtime.scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class AgentExecutionQueueScheduler {

    private final AgentExecutionClaimService claims;
    private final ExecutionWorkerCapacity capacity;

    public AgentExecutionQueueScheduler(
            AgentExecutionClaimService claims, ExecutionWorkerCapacity capacity) {
        this.claims = claims;
        this.capacity = capacity;
    }

    @Scheduled(fixedDelayString = "${data-agent.runtime.queue-scan-interval:1s}")
    public void claimQueuedExecutions() {
        while (capacity.available() > 0 && claims.claimNext()) {
            // Continue until the fixed tenant has no claimable work or the instance is full.
        }
    }
}
