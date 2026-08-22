package com.datagent.agentv2.conversation.summary;

import com.datagent.agentv2.conversation.runtime.AgentExecutionRuntimeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每两秒从 PostgreSQL 收敛本实例订阅者的会话摘要快照。 */
@Component
@RequiredArgsConstructor
public final class ConversationSummaryReconciler {

    private final ConversationSummaryStreamFacade summaryStream;
    private final AgentExecutionRuntimeProperties runtime;

    @Scheduled(
            fixedDelayString = "${data-agent.runtime.conversation-summary-reconcile-interval:2s}")
    public void reconcile() {
        if (runtime.schedulingEnabled()) {
            summaryStream.reconcileSubscribers();
        }
    }
}
