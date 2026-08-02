package com.datagent.agentv2.web.conversation;

import com.datagent.agentv2.conversation.application.ConversationApplicationService;
import com.datagent.agentv2.conversation.application.ConversationView;
import com.datagent.agentv2.conversation.cluster.AgentExecutionDeltaNotification;
import com.datagent.agentv2.conversation.runtime.AgentExecutionRuntimeProperties;
import com.datagent.agentv2.conversation.stream.CompositeSseCursor;
import com.datagent.agentv2.conversation.stream.ExecutionStreamResynchronizer;
import com.datagent.agentv2.conversation.stream.LocalSseRegistry;
import com.datagent.agentv2.persistence.service.AgentExecutionEventPersisted;
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
    private final AgentExecutionRuntimeProperties runtime;
    private final ConcurrentMap<SessionKey, Set<Subscription>> subscriptions =
            new ConcurrentHashMap<>();

    public ConversationEventStreamFacade(
            ConversationApplicationService conversations,
            LocalSseRegistry localStreams,
            AgentExecutionRuntimeProperties runtime) {
        this.conversations = conversations;
        this.localStreams = localStreams;
        this.runtime = runtime;
    }

    public SseEmitter open(String conversationSessionId, String lastExecutionEventId) {
        String tenantId = TenantContext.requiredTenantId();
        conversations.requireAccessibleSession(conversationSessionId);
        CompositeSseCursor cursor = CompositeSseCursor.parse(lastExecutionEventId);
        SseEmitter emitter = new SseEmitter(0L);
        Subscription subscription =
                new Subscription(tenantId, conversationSessionId, emitter, cursor);

        // 先建立本地缓冲与 Redis 动态订阅，再读取持久化 replay，消除 subscribe gap。
        LocalSseRegistry.Registration localRegistration =
                localStreams.register(
                        tenantId,
                        conversationSessionId,
                        new LocalSseRegistry.Subscriber() {
                            @Override
                            public void onDelta(AgentExecutionDeltaNotification envelope) {
                                acceptDelta(subscription, envelope);
                            }

                            @Override
                            public void onGap(AgentExecutionDeltaNotification envelope) {
                                acceptDelta(subscription, envelope);
                            }
                        });
        subscription.localRegistration = localRegistration;
        SessionKey key = new SessionKey(tenantId, conversationSessionId);
        subscriptions
                .computeIfAbsent(key, ignored -> new CopyOnWriteArraySet<>())
                .add(subscription);
        emitter.onCompletion(() -> remove(subscription));
        emitter.onTimeout(() -> remove(subscription));
        emitter.onError(ignored -> remove(subscription));

        // replay 后发送数据库 snapshot，最后按坐标刷新握手期间到达的缓冲。
        conversations
                .replayEvents(conversationSessionId, cursor.executionEventSequence())
                .forEach(subscription::acceptEvent);
        ConversationApplicationService.ExecutionStreamCheckpointView snapshot =
                conversations.currentStream(conversationSessionId);
        if (snapshot != null) {
            sendSnapshot(subscription, snapshot);
        }
        subscription.finishInitialization();
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void publish(AgentExecutionEventPersisted saved) {
        AgentExecutionEventPersisted notification = saved;
        SessionKey key =
                new SessionKey(
                        notification.tenantId(), notification.event().getConversationSessionId());
        Set<Subscription> sessionSubscriptions = subscriptions.get(key);
        if (sessionSubscriptions == null || sessionSubscriptions.isEmpty()) {
            return;
        }
        ConversationView.AgentExecutionEventView event =
                conversations.eventView(notification.event());
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
                                key.conversationSessionId(),
                                values.stream()
                                        .mapToLong(
                                                subscription ->
                                                        subscription
                                                                .cursor()
                                                                .executionEventSequence())
                                        .min()
                                        .orElse(0L)));
        ConversationApplicationService.SessionStreamRecoveryView reconciliation =
                conversations.reconcileStreams(afterSequenceBySession);
        tenantSubscriptions.forEach(
                (key, values) ->
                        reconcileSession(
                                values,
                                reconciliation
                                        .eventsBySession()
                                        .getOrDefault(key.conversationSessionId(), List.of()),
                                reconciliation
                                        .snapshotsBySession()
                                        .get(key.conversationSessionId()),
                                now));
    }

    private void reconcileSession(
            Set<Subscription> values,
            List<ConversationView.AgentExecutionEventView> events,
            ConversationApplicationService.ExecutionStreamCheckpointView snapshot,
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

    private void acceptDelta(Subscription subscription, AgentExecutionDeltaNotification envelope) {
        subscription.acceptDelta(envelope);
    }

    private void sendSnapshot(
            Subscription subscription,
            ConversationApplicationService.ExecutionStreamCheckpointView snapshot) {
        // snapshot 与并发 delta 共用连接锁，确保发送顺序与复合 cursor 一致。
        synchronized (subscription) {
            ExecutionStreamResynchronizer.Snapshot position =
                    new ExecutionStreamResynchronizer.Snapshot(
                            snapshot.executionId(),
                            snapshot.executionAttemptNumber(),
                            snapshot.modelInvocationSequence(),
                            snapshot.outputDeltaSequence());
            boolean snapshotIdentityChanged =
                    !snapshot.executionId().equals(subscription.snapshotExecutionId);
            if (!snapshotIdentityChanged && !subscription.resynchronizer.canAdvance(position)) {
                return;
            }
            List<AgentExecutionDeltaNotification> ready =
                    subscription.resynchronizer.apply(position, Instant.now());
            CompositeSseCursor cursor =
                    subscription
                            .cursor()
                            .withStream(
                                    snapshot.executionAttemptNumber(),
                                    snapshot.modelInvocationSequence(),
                                    snapshot.outputDeltaSequence());
            subscription.cursor = cursor;
            subscription.snapshotExecutionId = snapshot.executionId();
            subscription.localRegistration.resume(
                    snapshot.executionId(),
                    snapshot.executionAttemptNumber(),
                    snapshot.modelInvocationSequence(),
                    snapshot.outputDeltaSequence());
            subscription.send(
                    EVENT_STREAM_SNAPSHOT,
                    cursor,
                    new StreamSnapshotEvent(
                            EVENT_STREAM_SNAPSHOT,
                            subscription.conversationSessionId,
                            snapshot.executionId(),
                            snapshot.executionAttemptNumber(),
                            snapshot.modelInvocationSequence(),
                            snapshot.outputDeltaSequence(),
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
                new SessionKey(subscription.tenantId, subscription.conversationSessionId),
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
        private final String conversationSessionId;
        private final SseEmitter emitter;
        private final ExecutionStreamResynchronizer resynchronizer;
        private final ThreadPoolExecutor sender;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final List<ConversationView.AgentExecutionEventView> pendingEvents =
                new ArrayList<>();
        private final List<AgentExecutionDeltaNotification> pendingDeltas = new ArrayList<>();
        private volatile CompositeSseCursor cursor;
        private volatile String snapshotExecutionId;
        private volatile boolean initializing = true;
        private LocalSseRegistry.Registration localRegistration;

        private Subscription(
                String tenantId,
                String conversationSessionId,
                SseEmitter emitter,
                CompositeSseCursor cursor) {
            this.tenantId = tenantId;
            this.conversationSessionId = conversationSessionId;
            this.emitter = emitter;
            this.cursor = cursor;
            this.resynchronizer = new ExecutionStreamResynchronizer(cursor);
            this.sender = sender();
        }

        private synchronized void acceptEvent(ConversationView.AgentExecutionEventView event) {
            if (event.executionEventSequence() <= cursor.executionEventSequence()) {
                return;
            }
            if (initializing) {
                pendingEvents.add(event);
                return;
            }
            sendEvent(event);
        }

        private synchronized void acceptDelta(AgentExecutionDeltaNotification envelope) {
            if (initializing) {
                pendingDeltas.add(envelope);
                if (pendingDeltas.size() > ExecutionStreamResynchronizer.MAX_BUFFERED_DELTAS) {
                    closeWithError(this, new IllegalStateException("SSE initialization overflow"));
                }
                return;
            }
            resynchronizer.accept(envelope, Instant.now()).forEach(this::sendDelta);
        }

        private synchronized void finishInitialization() {
            pendingEvents.stream()
                    .sorted(
                            Comparator.comparingLong(
                                    ConversationView.AgentExecutionEventView
                                            ::executionEventSequence))
                    .forEach(this::sendEvent);
            pendingDeltas.stream()
                    .sorted(
                            Comparator.comparingLong(
                                            AgentExecutionDeltaNotification::executionAttemptNumber)
                                    .thenComparingLong(
                                            AgentExecutionDeltaNotification
                                                    ::modelInvocationSequence)
                                    .thenComparingLong(
                                            AgentExecutionDeltaNotification::outputDeltaSequence))
                    .flatMap(envelope -> resynchronizer.accept(envelope, Instant.now()).stream())
                    .forEach(this::sendDelta);
            pendingEvents.clear();
            pendingDeltas.clear();
            initializing = false;
        }

        private void sendEvent(ConversationView.AgentExecutionEventView event) {
            cursor = cursor.withEvent(event.executionEventSequence());
            send(
                    event.type(),
                    cursor,
                    new LifecycleEvent(
                            event.type(),
                            conversationSessionId,
                            event.executionId(),
                            event.executionEventSequence(),
                            event.occurredAt(),
                            new LifecycleEventPayload(event.status())));
        }

        private void sendDelta(AgentExecutionDeltaNotification envelope) {
            cursor =
                    cursor.withStream(
                            envelope.executionAttemptNumber(),
                            envelope.modelInvocationSequence(),
                            envelope.outputDeltaSequence());
            send(
                    envelope.deltaType(),
                    cursor,
                    new DeltaEvent(
                            envelope.deltaType(),
                            conversationSessionId,
                            envelope.executionId(),
                            envelope.executionAttemptNumber(),
                            envelope.modelInvocationSequence(),
                            envelope.outputDeltaSequence(),
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

        private boolean needs(
                ConversationApplicationService.ExecutionStreamCheckpointView snapshot) {
            CompositeSseCursor current = cursor;
            return !snapshot.executionId().equals(snapshotExecutionId)
                    || resynchronizer.needsSnapshot()
                    || snapshot.executionAttemptNumber() > current.executionAttemptNumber()
                    || (snapshot.executionAttemptNumber() == current.executionAttemptNumber()
                            && snapshot.modelInvocationSequence()
                                    > current.modelInvocationSequence())
                    || (snapshot.executionAttemptNumber() == current.executionAttemptNumber()
                            && snapshot.modelInvocationSequence()
                                    == current.modelInvocationSequence()
                            && snapshot.outputDeltaSequence() > current.outputDeltaSequence());
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

    private record SessionKey(String tenantId, String conversationSessionId) {}

    private record LifecycleEvent(
            String type,
            String conversationSessionId,
            String executionId,
            long executionEventSequence,
            Instant occurredAt,
            LifecycleEventPayload payload) {}

    /**
     * 生命周期事件的固定状态载荷。
     *
     * @param status Agent 执行状态
     */
    private record LifecycleEventPayload(String status) {}

    private record DeltaEvent(
            String type,
            String conversationSessionId,
            String executionId,
            long executionAttemptNumber,
            long modelInvocationSequence,
            long outputDeltaSequence,
            String delta,
            Instant occurredAt) {}

    private record StreamSnapshotEvent(
            String type,
            String conversationSessionId,
            String executionId,
            long executionAttemptNumber,
            long modelInvocationSequence,
            long outputDeltaSequence,
            String phase,
            String reasoning,
            String answer,
            Instant checkpointedAt,
            Instant occurredAt) {}
}
