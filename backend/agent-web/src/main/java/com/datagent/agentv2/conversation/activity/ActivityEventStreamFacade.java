package com.datagent.agentv2.conversation.activity;

import com.datagent.agentv2.conversation.enums.ActivityEventType;
import com.datagent.agentv2.conversation.enums.SessionActivityStatus;
import com.datagent.agentv2.conversation.session.ConversationApplicationService;
import com.datagent.agentv2.conversation.session.ConversationView;
import com.datagent.agentv2.platform.context.OperatorContext;
import com.datagent.agentv2.platform.context.OperatorIdentity;
import com.datagent.agentv2.platform.context.TenantContext;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 用户级 activity SSE：只发送数据库可重建的当前快照，不承诺永久事件历史。 */
@Service
public final class ActivityEventStreamFacade {

    private static final String ERROR_CONNECTION_LIMIT = "Activity SSE connection limit exceeded";
    private static final String ERROR_SLOW_CLIENT = "Activity SSE client is too slow";
    private static final String SENDER_THREAD_PREFIX = "activity-sse-";
    private static final int MAX_CONNECTIONS = 256;
    private static final int MAX_PENDING_SENDS = 16;
    private static final AtomicLong CONNECTION_SEQUENCE = new AtomicLong();

    private final ConversationApplicationService conversations;
    private final ConcurrentMap<UserKey, Set<Subscription>> subscriptions =
            new ConcurrentHashMap<>();
    private final AtomicInteger connectionCount = new AtomicInteger();

    public ActivityEventStreamFacade(ConversationApplicationService conversations) {
        this.conversations = conversations;
    }

    public SseEmitter open() {
        OperatorIdentity operator = OperatorContext.requiredOperator();
        if (connectionCount.incrementAndGet() > MAX_CONNECTIONS) {
            connectionCount.decrementAndGet();
            throw new IllegalStateException(ERROR_CONNECTION_LIMIT);
        }

        SseEmitter emitter = new SseEmitter(0L);
        Subscription subscription = new Subscription(operator, emitter);
        UserKey key = new UserKey(operator.tenantId(), operator.userId());
        subscriptions
                .computeIfAbsent(key, ignored -> new CopyOnWriteArraySet<>())
                .add(subscription);
        emitter.onCompletion(() -> remove(key, subscription));
        emitter.onTimeout(() -> remove(key, subscription));
        emitter.onError(ignored -> remove(key, subscription));

        // 注册完成后读取数据库快照，避免初始读取与后续 reconciliation 之间出现空窗。
        subscription.accept(snapshot());
        return emitter;
    }

    /** 每轮只遍历本实例仍有连接的用户，并在其租户/操作者上下文中读取数据库。 */
    public void reconcileActiveUsers() {
        subscriptions.forEach(
                (key, values) -> {
                    if (values.isEmpty()) {
                        return;
                    }
                    OperatorIdentity operator = values.iterator().next().operator;
                    TenantContext.run(
                            key.tenantId(),
                            () ->
                                    OperatorContext.run(
                                            operator,
                                            () -> {
                                                ActivitySnapshot snapshot = snapshot();
                                                values.forEach(value -> value.accept(snapshot));
                                            }));
                });
    }

    int activeUserCount() {
        return subscriptions.size();
    }

    private ActivitySnapshot snapshot() {
        List<ConversationView> visible = conversations.list();
        long orderingVersion = visible.isEmpty() ? 0L : visible.getFirst().orderingVersion();
        List<ActivityItem> items = new ArrayList<>(visible.size());
        for (ConversationView session : visible) {
            items.add(
                    new ActivityItem(
                            session.conversationSessionId(),
                            session.execution() == null
                                    ? SessionActivityStatus.IDLE
                                    : SessionActivityStatus.fromExecutionStatus(
                                            session.execution().status()),
                            session.unread(),
                            session.lastNotifiableEventSequence(),
                            session.sortOrder(),
                            session.updatedAt()));
        }
        return new ActivitySnapshot(
                ActivityEventType.SNAPSHOT, orderingVersion, List.copyOf(items), Instant.now());
    }

    private void remove(UserKey key, Subscription subscription) {
        if (!subscription.closed.compareAndSet(false, true)) {
            return;
        }
        subscriptions.computeIfPresent(
                key,
                (ignored, values) -> {
                    values.remove(subscription);
                    return values.isEmpty() ? null : values;
                });
        subscription.sender.shutdownNow();
        connectionCount.decrementAndGet();
    }

    private final class Subscription {

        private final OperatorIdentity operator;
        private final SseEmitter emitter;
        private final ThreadPoolExecutor sender;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Fingerprint lastFingerprint;

        private Subscription(OperatorIdentity operator, SseEmitter emitter) {
            this.operator = operator;
            this.emitter = emitter;
            this.sender = sender();
        }

        private synchronized void accept(ActivitySnapshot snapshot) {
            Fingerprint fingerprint =
                    new Fingerprint(snapshot.orderingVersion(), snapshot.sessions());
            if (fingerprint.equals(lastFingerprint) || closed.get()) {
                return;
            }
            lastFingerprint = fingerprint;
            try {
                sender.execute(
                        () -> {
                            try {
                                emitter.send(
                                        SseEmitter.event()
                                                .name(ActivityEventType.SNAPSHOT.value())
                                                .data(snapshot, MediaType.APPLICATION_JSON));
                            } catch (IOException | IllegalStateException failure) {
                                remove(new UserKey(operator.tenantId(), operator.userId()), this);
                            }
                        });
            } catch (RejectedExecutionException failure) {
                remove(new UserKey(operator.tenantId(), operator.userId()), this);
                emitter.completeWithError(new IllegalStateException(ERROR_SLOW_CLIENT));
            }
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
                        Thread thread = new Thread(runnable, SENDER_THREAD_PREFIX + connection);
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
        }
    }

    private record UserKey(String tenantId, String userId) {}

    private record Fingerprint(long orderingVersion, List<ActivityItem> sessions) {}

    public record ActivitySnapshot(
            ActivityEventType type,
            long orderingVersion,
            List<ActivityItem> sessions,
            Instant snapshotAt) {}

    public record ActivityItem(
            String conversationSessionId,
            SessionActivityStatus status,
            boolean unread,
            long lastNotifiableEventSequence,
            long sortOrder,
            LocalDateTime updatedAt) {}
}
