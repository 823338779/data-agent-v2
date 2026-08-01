package com.datagent.agentv2.conversation.activity;

import com.datagent.agentv2.conversation.runtime.AgentExecutionRuntimeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每两秒从 PostgreSQL 收敛本实例活跃用户的 activity 快照。 */
@Component
@RequiredArgsConstructor
public final class ActivityReconciler {

    private final ActivityEventStreamFacade activityEvents;
    private final AgentExecutionRuntimeProperties runtime;

    @Scheduled(fixedDelayString = "${data-agent.runtime.activity-reconcile-interval:2s}")
    public void reconcile() {
        if (runtime.schedulingEnabled()) {
            activityEvents.reconcileActiveUsers();
        }
    }
}
