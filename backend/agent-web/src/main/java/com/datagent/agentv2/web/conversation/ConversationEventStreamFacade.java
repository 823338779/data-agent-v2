package com.datagent.agentv2.web.conversation;

import com.datagent.agentv2.conversation.application.ConversationApplicationService;
import com.datagent.agentv2.conversation.application.ConversationView;
import com.datagent.agentv2.conversation.cluster.StreamDeltaEnvelope;
import com.datagent.agentv2.conversation.runtime.RunRuntimeProperties;
import com.datagent.agentv2.conversation.stream.CompositeSseCursor;
import com.datagent.agentv2.conversation.stream.LocalSseRegistry;
import com.datagent.agentv2.conversation.stream.StreamResynchronizer;
import com.datagent.agentv2.persistence.service.AgentRunEventSaved;
import com.datagent.agentv2.platform.context.TenantContext;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 详细会话 SSE：持久事件可重放，瞬时 delta 由 Redis 与 checkpoint 共同收敛。 */
@Service
public final class ConversationEventStreamFacade {

    private static final String EVENT_STREAM_SNAPSHOT = "stream_snapshot";
    private static final int MAX_PENDING_SENDS = 256;
    private static final AtomicLong CONNECTION_SEQUENCE = new AtomicLong();

    private final ConversationApplicationService conversations;
    private final LocalSseRegistry localStreams;
    private final RunRuntimeProperties runtime;
    private final ConcurrentMap<SessionKey, Set<Subscription>> subscriptions =
            new ConcurrentHashMap<>();

    public ConversationEventStreamFacade(
            ConversationApplicationService conversations,
            LocalSseRegistry localStreams,
            RunRuntimeProperties runtime) {
        this.conversations = conversations;
        this.localStreams = localStreams;
        this.runtime = runtime;
    }

    public SseEmitter open(String sessionId, String lastEventId) {
        String tenantId = TenantContext.requiredTenantId();
        conversations.requireAccessibleSession(sessionId);
        CompositeSseCursor cursor = CompositeSseCursor.parse(lastEventId);
        SseEmitter emitter = new SseEmitter(0L);
        Subscription subscription = new Subscription(tenantId, sessionId, emitter, cursor);

        // 先建立本地缓冲与 Redis 动态订阅，再读取持久化 replay，消除 subscribe gap。
        LocalSseRegistry.Registration localRegistration =
                localStreams.register(
                        tenantId,
                        sessionId,
                        new LocalSseRegistry.Subscriber() {
                            @Override
                            public void onDelta(StreamDeltaEnvelope envelope) {
                                acceptDelta(subscription, envelope);
                            }

                            @Override
                            public void onGap(StreamDeltaEnvelope envelope) {
                                acceptDelta(subscription, envelope);
                            }
                        });
        subscription.localRegistration = localRegistration;
        SessionKey key = new SessionKey(tenantId, sessionId);
        subscriptions
                .computeIfAbsent(key, ignored -> new CopyOnWriteArraySet<>())
                .add(subscription);
        emitter.onCompletion(() -> remove(subscription));
        emitter.onTimeout(() -> remove(subscription));
        emitter.onError(ignored -> remove(subscription));

        // replay 后发送数据库 snapshot，最后按坐标刷新握手期间到达的缓冲。
        conversations
                .replayEvents(sessionId, cursor.eventSequence())
                .forEach(subscription::acceptEvent);
        ConversationApplicationService.StreamSnapshotView snapshot =
                conversations.currentStream(sessionId);
        if (snapshot != null) {
            sendSnapshot(subscription, snapshot);
        }
        subscription.finishInitialization();
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void publish(AgentRunEventSaved saved) {
        AgentRunEventSaved notification = saved;
        SessionKey key =
                new SessionKey(
                        notification.event().getTenantId(), notification.event().getSessionId());
        Set<Subscription> sessionSubscriptions = subscriptions.get(key);
        if (sessionSubscriptions == null || sessionSubscriptions.isEmpty()) {
            return;
        }
        ConversationView.RunEventView event = conversations.eventView(notification.event());
        sessionSubscriptions.forEach(subscription -> subscription.acceptEvent(event));
    }

    /** 每秒仅扫描本实例仍有详细连接的会话，补齐持久事件与 checkpoint。 */
    public void reconcileActiveSessions() {
        Instant now = Instant.now();
        Map<String, Map<SessionKey, Set<Subscription>>> subscriptionsByTenant = new HashMap<>();
        subscriptions.forEach(
                (key, values) ->
                        subscriptionsByTenant
                                .computeIfAbsent(key.tenantId(), ignored -> new HashMap<>())
                                .put(key, values));
        subscriptionsByTenant.forEach(
                (tenantId, tenantSubscriptions) ->
                        TenantContext.run(
                                tenantId, () -> reconcileTenant(tenantSubscriptions, now)));
    }

    private void reconcileTenant(
            Map<SessionKey, Set<Subscription>> tenantSubscriptions, Instant now) {
        Map<String, Long> afterSequenceBySession = new HashMap<>();
        tenantSubscriptions.forEach(
                (key, values) ->
                        afterSequenceBySession.put(
                                key.sessionId(),
                                values.stream()
                                        .mapToLong(
                                                subscription ->
                                                        subscription.cursor().eventSequence())
                                        .min()
                                        .orElse(0L)));
        ConversationApplicationService.StreamReconciliationView reconciliation =
                conversations.reconcileStreams(afterSequenceBySession);
        tenantSubscriptions.forEach(
                (key, values) ->
                        reconcileSession(
                                values,
                                reconciliation
                                        .eventsBySession()
                                        .getOrDefault(key.sessionId(), List.of()),
                                reconciliation.snapshotsBySession().get(key.sessionId()),
                                now));
    }

    private void reconcileSession(
            Set<Subscription> values,
            List<ConversationView.RunEventView> events,
            ConversationApplicationService.StreamSnapshotView snapshot,
            Instant now) {
        for (Subscription subscription : values) {
            events.forEach(subscription::acceptEvent);
            if (snapshot != null && subscription.needs(snapshot)) {
                sendSnapshot(subscription, snapshot);
            }
            if (subscription.resynchronizer.shouldClose(now, runtime.leaseDuration())) {
                closeWithError(
                        subscription, new IllegalStateException("SSE stream gap did not converge"));
            }
        }
    }

    private void acceptDelta(Subscription subscription, StreamDeltaEnvelope envelope) {
        subscription.acceptDelta(envelope);
    }

    private void sendSnapshot(
            Subscription subscription, ConversationApplicationService.StreamSnapshotView snapshot) {
        // snapshot 与并发 delta 共用连接锁，确保发送顺序与复合 cursor 一致。
        synchronized (subscription) {
            StreamResynchronizer.Snapshot position =
                    new StreamResynchronizer.Snapshot(
                            snapshot.runId(),
                            snapshot.executionAttempt(),
                            snapshot.modelTurnSequence(),
                            snapshot.streamSequence());
            boolean snapshotIdentityChanged = !snapshot.runId().equals(subscription.snapshotRunId);
            if (!snapshotIdentityChanged && !subscription.resynchronizer.canAdvance(position)) {
                return;
            }
            List<StreamDeltaEnvelope> ready =
                    subscription.resynchronizer.apply(position, Instant.now());
            CompositeSseCursor cursor =
                    subscription
                            .cursor()
                            .withStream(
                                    snapshot.executionAttempt(),
                                    snapshot.modelTurnSequence(),
                                    snapshot.streamSequence());
            subscription.cursor = cursor;
            subscription.snapshotRunId = snapshot.runId();
            subscription.localRegistration.resume(
                    snapshot.runId(),
                    snapshot.executionAttempt(),
                    snapshot.modelTurnSequence(),
                    snapshot.streamSequence());
            subscription.send(
                    EVENT_STREAM_SNAPSHOT,
                    cursor,
                    new StreamSnapshotEvent(
                            EVENT_STREAM_SNAPSHOT,
                            subscription.sessionId,
                            snapshot.runId(),
                            snapshot.executionAttempt(),
                            snapshot.modelTurnSequence(),
                            snapshot.streamSequence(),
                            snapshot.phase(),
                            snapshot.reasoning(),
                            snapshot.answer(),
                            snapshot.checkpointedAt(),
                            Instant.now()));
            ready.forEach(subscription::sendDelta);
        }
    }

    private void remove(Subscription subscription) {
        if (!subscription.closed.compareAndSet(false, true)) {
            return;
        }
        subscriptions.computeIfPresent(
                new SessionKey(subscription.tenantId, subscription.sessionId),
                (ignored, values) -> {
                    values.remove(subscription);
                    return values.isEmpty() ? null : values;
                });
        if (subscription.localRegistration != null) {
            subscription.localRegistration.close();
        }
        subscription.sender.shutdownNow();
    }

    private void closeWithError(Subscription subscription, RuntimeException failure) {
        remove(subscription);
        try {
            subscription.emitter.completeWithError(failure);
        } catch (IllegalStateException ignored) {
            // Servlet 容器已经完成该连接时无需再次关闭。
        }
    }

    private final class Subscription {

        private final String tenantId;
        private final String sessionId;
        private final SseEmitter emitter;
        private final StreamResynchronizer resynchronizer;
        private final ThreadPoolExecutor sender;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final List<ConversationView.RunEventView> pendingEvents = new ArrayList<>();
        private final List<StreamDeltaEnvelope> pendingDeltas = new ArrayList<>();
        private volatile CompositeSseCursor cursor;
        private volatile String snapshotRunId;
        private volatile boolean initializing = true;
        private LocalSseRegistry.Registration localRegistration;

        private Subscription(
                String tenantId, String sessionId, SseEmitter emitter, CompositeSseCursor cursor) {
            this.tenantId = tenantId;
            this.sessionId = sessionId;
            this.emitter = emitter;
            this.cursor = cursor;
            this.resynchronizer = new StreamResynchronizer(cursor);
            this.sender = sender();
        }

        private synchronized void acceptEvent(ConversationView.RunEventView event) {
            if (event.sequence() <= cursor.eventSequence()) {
                return;
            }
            if (initializing) {
                pendingEvents.add(event);
                return;
            }
            sendEvent(event);
        }

        private synchronized void acceptDelta(StreamDeltaEnvelope envelope) {
            if (initializing) {
                pendingDeltas.add(envelope);
                if (pendingDeltas.size() > StreamResynchronizer.MAX_BUFFERED_DELTAS) {
                    closeWithError(this, new IllegalStateException("SSE initialization overflow"));
                }
                return;
            }
            resynchronizer.accept(envelope, Instant.now()).forEach(this::sendDelta);
        }

        private synchronized void finishInitialization() {
            pendingEvents.stream()
                    .sorted(Comparator.comparingLong(ConversationView.RunEventView::sequence))
                    .forEach(this::sendEvent);
            pendingDeltas.stream()
                    .sorted(
                            Comparator.comparingLong(StreamDeltaEnvelope::executionAttempt)
                                    .thenComparingLong(StreamDeltaEnvelope::modelTurnSequence)
                                    .thenComparingLong(StreamDeltaEnvelope::streamSequence))
                    .flatMap(envelope -> resynchronizer.accept(envelope, Instant.now()).stream())
                    .forEach(this::sendDelta);
            pendingEvents.clear();
            pendingDeltas.clear();
            initializing = false;
        }

        private void sendEvent(ConversationView.RunEventView event) {
            cursor = cursor.withEvent(event.sequence());
            send(
                    event.type(),
                    cursor,
                    new LifecycleEvent(
                            event.type(),
                            sessionId,
                            event.runId(),
                            event.sequence(),
                            event.occurredAt(),
                            Map.of("status", event.status())));
        }

        private void sendDelta(StreamDeltaEnvelope envelope) {
            cursor =
                    cursor.withStream(
                            envelope.executionAttempt(),
                            envelope.modelTurnSequence(),
                            envelope.streamSequence());
            send(
                    envelope.deltaType(),
                    cursor,
                    new DeltaEvent(
                            envelope.deltaType(),
                            sessionId,
                            envelope.runId(),
                            envelope.executionAttempt(),
                            envelope.modelTurnSequence(),
                            envelope.streamSequence(),
                            envelope.delta(),
                            envelope.occurredAt()));
        }

        private void send(String eventName, CompositeSseCursor eventCursor, Object data) {
            if (closed.get()) {
                return;
            }
            try {
                sender.execute(
                        () -> {
                            try {
                                emitter.send(
                                        SseEmitter.event()
                                                .id(eventCursor.encode())
                                                .name(eventName)
                                                .data(data, MediaType.APPLICATION_JSON));
                            } catch (IOException | IllegalStateException failure) {
                                closeWithError(this, new IllegalStateException(failure));
                            }
                        });
            } catch (RejectedExecutionException failure) {
                closeWithError(this, new IllegalStateException("SSE client is too slow", failure));
            }
        }

        private CompositeSseCursor cursor() {
            return cursor;
        }

        private boolean needs(ConversationApplicationService.StreamSnapshotView snapshot) {
            CompositeSseCursor current = cursor;
            return !snapshot.runId().equals(snapshotRunId)
                    || resynchronizer.needsSnapshot()
                    || snapshot.executionAttempt() > current.executionAttempt()
                    || (snapshot.executionAttempt() == current.executionAttempt()
                            && snapshot.modelTurnSequence() > current.modelTurnSequence())
                    || (snapshot.executionAttempt() == current.executionAttempt()
                            && snapshot.modelTurnSequence() == current.modelTurnSequence()
                            && snapshot.streamSequence() > current.streamSequence());
        }

        private ThreadPoolExecutor sender() {
            long connection = CONNECTION_SEQUENCE.incrementAndGet();
            return new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(MAX_PENDING_SENDS),
                    runnable -> {
                        Thread thread = new Thread(runnable, "session-sse-" + connection);
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
        }
    }

    private record SessionKey(String tenantId, String sessionId) {}

    private record LifecycleEvent(
            String type,
            String sessionId,
            String runId,
            long sequence,
            Instant occurredAt,
            Map<String, Object> payload) {}

    private record DeltaEvent(
            String type,
            String sessionId,
            String runId,
            long executionAttempt,
            long modelTurnSequence,
            long streamSequence,
            String delta,
            Instant occurredAt) {}

    private record StreamSnapshotEvent(
            String type,
            String sessionId,
            String runId,
            long executionAttempt,
            long modelTurnSequence,
            long streamSequence,
            String phase,
            String reasoning,
            String answer,
            Instant checkpointedAt,
            Instant occurredAt) {}
}
