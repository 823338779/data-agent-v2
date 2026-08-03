package com.datagent.agentv2.conversation.activity;

import com.datagent.agentv2.conversation.domain.AgentExecutionEvent;
import com.datagent.agentv2.conversation.enums.ExecutionEventType;
import com.datagent.agentv2.persistence.service.AgentExecutionEventPersisted;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.persistence.service.ConversationSessionUserStateService;
import com.datagent.agentv2.platform.context.OperatorContext;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SessionActivityService {

    private static final Set<String> NOTIFIABLE_EVENT_TYPES =
            Set.of(
                    ExecutionEventType.DONE.value(),
                    ExecutionEventType.FAILED.value(),
                    ExecutionEventType.WAITING.value());

    private final ConversationSessionService sessions;
    private final ConversationSessionUserStateService states;
    private final SessionOrderingService ordering;

    @Transactional
    public void markRead(String conversationSessionId, long upToSequence) {
        ordering.ensureVisibleSessions();
        states.advanceReadCursor(
                OperatorContext.requiredOperator().userId(), conversationSessionId, upToSequence);
    }

    @EventListener
    @Transactional
    public void recordPersistedEvent(AgentExecutionEventPersisted persisted) {
        AgentExecutionEvent event = persisted.event();
        Long sequence =
                NOTIFIABLE_EVENT_TYPES.contains(event.getType())
                        ? event.getExecutionEventSequence()
                        : null;
        sessions.recordActivity(event.getConversationSessionId(), sequence, event.getOccurredAt());
    }
}
