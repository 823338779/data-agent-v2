package com.datagent.agentv2.web.conversation;

import com.datagent.agentv2.conversation.application.ConversationApplicationService;
import com.datagent.agentv2.conversation.application.ConversationView;
import com.datagent.agentv2.persistence.service.AgentRunEventSaved;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ConversationEventStreamFacade {

    private final ConversationApplicationService conversations;

    /** Active same-process SSE subscriptions grouped by globally unique session ID. */
    private final ConcurrentMap<String, Set<Subscription>> subscriptions =
            new ConcurrentHashMap<>();

    public ConversationEventStreamFacade(ConversationApplicationService conversations) {
        this.conversations = conversations;
    }

    public SseEmitter open(String sessionId) {
        // Authorize first, then register before replay so an event cannot fall into a subscribe gap.
        conversations.requireAccessibleSession(sessionId);
        SseEmitter emitter = new SseEmitter(0L);
        Subscription subscription = new Subscription(sessionId, emitter);
        subscriptions
                .computeIfAbsent(sessionId, ignored -> new CopyOnWriteArraySet<>())
                .add(subscription);
        emitter.onCompletion(() -> remove(subscription));
        emitter.onTimeout(() -> remove(subscription));
        emitter.onError(ignored -> remove(subscription));

        // Replay after registration; per-subscription IDs suppress overlap with concurrent live sends.
        List<ConversationView.RunEventView> persistedEvents = conversations.replayEvents(sessionId);
        for (ConversationView.RunEventView event : persistedEvents) {
            send(subscription, event);
        }
        return emitter;
    }

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void publish(AgentRunEventSaved saved) {
        // Avoid database mapping work when no browser is currently watching this session.
        Set<Subscription> sessionSubscriptions =
                subscriptions.get(saved.event().getSessionId());
        if (sessionSubscriptions == null || sessionSubscriptions.isEmpty()) {
            return;
        }
        ConversationView.RunEventView event = conversations.eventView(saved.event());
        for (Subscription subscription : sessionSubscriptions) {
            send(subscription, event);
        }
    }

    private void send(Subscription subscription, ConversationView.RunEventView event) {
        synchronized (subscription) {
            if (!subscription.sentEventIds.add(event.id())) {
                return;
            }
            try {
                subscription.emitter.send(
                        SseEmitter.event()
                                .id(event.id())
                                .name(event.type())
                                .data(event, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException failure) {
                remove(subscription);
                subscription.emitter.completeWithError(failure);
            }
        }
    }

    private void remove(Subscription subscription) {
        subscriptions.computeIfPresent(
                subscription.sessionId,
                (ignored, current) -> {
                    current.remove(subscription);
                    return current.isEmpty() ? null : current;
                });
    }

    private static final class Subscription {

        /** Session whose committed events are routed to this connection. */
        private final String sessionId;

        /** Open servlet response used to write SSE frames. */
        private final SseEmitter emitter;

        /** Event identifiers already delivered through live publication or replay. */
        private final Set<String> sentEventIds = new HashSet<>();

        private Subscription(String sessionId, SseEmitter emitter) {
            this.sessionId = sessionId;
            this.emitter = emitter;
        }
    }
}
