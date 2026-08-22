package com.datagent.agentv2.conversation.activity;

import com.datagent.agentv2.conversation.domain.AgentExecutionEvent;
import com.datagent.agentv2.persistence.service.AgentExecutionEventPersisted;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SessionActivityService {

    private final ConversationSessionService sessions;

    @EventListener
    @Transactional
    public void recordPersistedEvent(AgentExecutionEventPersisted persisted) {
        AgentExecutionEvent event = persisted.event();
        sessions.recordActivity(event.getConversationSessionId(), event.getOccurredAt());
    }
}
