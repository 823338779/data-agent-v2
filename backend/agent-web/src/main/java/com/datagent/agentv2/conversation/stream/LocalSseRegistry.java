package com.datagent.agentv2.conversation.stream;

import com.datagent.agentv2.conversation.cluster.StreamDeltaEnvelope;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** 保存本实例的详细 SSE 连接，并在连接内完成流坐标去重与跳号识别。 */
@Component
public final class LocalSseRegistry {

    private static final ChannelLifecycle NOOP_LIFECYCLE = new ChannelLifecycle() {};

    private final ConcurrentMap<SessionKey, CopyOnWriteArrayList<Connection>> connections =
            new ConcurrentHashMap<>();
    private volatile ChannelLifecycle lifecycle = NOOP_LIFECYCLE;

    public void attachLifecycle(ChannelLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /** 首连接触发 Redis 订阅，末连接关闭时取消对应 channel。 */
    public Registration register(String tenantId, String sessionId, Subscriber subscriber) {
        SessionKey key = new SessionKey(tenantId, sessionId);
        Connection connection = new Connection(subscriber);
        connections.compute(
                key,
                (ignored, existing) -> {
                    CopyOnWriteArrayList<Connection> values =
                            existing == null ? new CopyOnWriteArrayList<>() : existing;
                    values.add(connection);
                    if (existing == null) {
                        lifecycle.subscribe(tenantId, sessionId);
                    }
                    return values;
                });
        return new Registration(() -> remove(key, connection));
    }

    /** owner 本地投递和 Redis 入站消息共用同一连接级排序规则。 */
    public void publish(StreamDeltaEnvelope envelope) {
        CopyOnWriteArrayList<Connection> sessionConnections =
                connections.get(new SessionKey(envelope.tenantId(), envelope.sessionId()));
        if (sessionConnections != null) {
            sessionConnections.forEach(connection -> connection.deliver(envelope));
        }
    }

    public boolean hasConnections(String tenantId, String sessionId) {
        CopyOnWriteArrayList<Connection> values =
                connections.get(new SessionKey(tenantId, sessionId));
        return values != null && !values.isEmpty();
    }

    private void remove(SessionKey key, Connection connection) {
        connections.computeIfPresent(
                key,
                (ignored, values) -> {
                    values.remove(connection);
                    if (values.isEmpty()) {
                        lifecycle.unsubscribe(key.tenantId(), key.sessionId());
                        return null;
                    }
                    return values;
                });
    }

    public interface Subscriber {
        void onDelta(StreamDeltaEnvelope envelope);

        void onGap(StreamDeltaEnvelope envelope);
    }

    public interface ChannelLifecycle {
        default void subscribe(String tenantId, String sessionId) {}

        default void unsubscribe(String tenantId, String sessionId) {}
    }

    public static final class Registration implements AutoCloseable {

        private final Runnable closeAction;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Registration(Runnable closeAction) {
            this.closeAction = closeAction;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closeAction.run();
            }
        }
    }

    private static final class Connection {

        private final Subscriber subscriber;
        private final ConcurrentMap<String, Cursor> cursors = new ConcurrentHashMap<>();

        private Connection(Subscriber subscriber) {
            this.subscriber = Objects.requireNonNull(subscriber, "subscriber");
        }

        private synchronized void deliver(StreamDeltaEnvelope envelope) {
            Cursor current = cursors.get(envelope.runId());
            Cursor incoming =
                    new Cursor(
                            envelope.executionAttempt(),
                            envelope.modelTurnSequence(),
                            envelope.streamSequence());
            if (current == null || incoming.isNewProjectionAfter(current)) {
                if (incoming.streamSequence() == 1) {
                    cursors.put(envelope.runId(), incoming);
                    subscriber.onDelta(envelope);
                } else {
                    subscriber.onGap(envelope);
                }
                return;
            }
            if (incoming.isOlderProjectionThan(current)
                    || incoming.streamSequence() <= current.streamSequence()) {
                return;
            }
            if (incoming.streamSequence() == current.streamSequence() + 1) {
                cursors.put(envelope.runId(), incoming);
                subscriber.onDelta(envelope);
            } else {
                subscriber.onGap(envelope);
            }
        }
    }

    private record SessionKey(String tenantId, String sessionId) {}

    private record Cursor(long executionAttempt, long modelTurnSequence, long streamSequence) {

        private boolean isNewProjectionAfter(Cursor other) {
            return executionAttempt > other.executionAttempt
                    || (executionAttempt == other.executionAttempt
                            && modelTurnSequence > other.modelTurnSequence);
        }

        private boolean isOlderProjectionThan(Cursor other) {
            return executionAttempt < other.executionAttempt
                    || (executionAttempt == other.executionAttempt
                            && modelTurnSequence < other.modelTurnSequence);
        }
    }
}
