package com.datagent.agentv2.conversation.runtime;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "data-agent.runtime.scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public final class AgentExecutionQueueScheduler {

    private final AgentExecutionClaimService claims;
    private final ExecutionWorkerCapacity capacity;

    @Scheduled(fixedDelayString = "${data-agent.runtime.queue-scan-interval:1s}")
    public void claimQueuedExecutions() {
        // 实例仍有执行容量时，持续尝试接管下一个排队任务。
        while (capacity.available() > 0) {
            boolean executionClaimed = claims.claimNext();
            if (!executionClaimed) {
                return;
            }
        }
    }
}
