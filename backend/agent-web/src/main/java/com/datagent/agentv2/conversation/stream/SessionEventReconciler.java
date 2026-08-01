package com.datagent.agentv2.conversation.stream;

import com.datagent.agentv2.conversation.runtime.RunRuntimeProperties;
import com.datagent.agentv2.web.conversation.ConversationEventStreamFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期补查本机活跃详细 SSE 的持久事件和 checkpoint，不依赖 Redis 可靠性。 */
@Component
@RequiredArgsConstructor
public final class SessionEventReconciler {

    private final ConversationEventStreamFacade streams;
    private final RunRuntimeProperties runtime;

    @Scheduled(fixedDelayString = "${data-agent.runtime.session-event-reconcile-interval:1s}")
    public void reconcile() {
        if (runtime.schedulingEnabled()) {
            streams.reconcileActiveSessions();
        }
    }
}
