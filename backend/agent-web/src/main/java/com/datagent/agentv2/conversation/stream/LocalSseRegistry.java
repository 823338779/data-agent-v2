package com.datagent.agentv2.conversation.stream;

import com.datagent.agentv2.conversation.cluster.AgentExecutionDeltaNotification;
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
    public Registration register(
            String tenantId, String conversationSessionId, Subscriber subscriber) {
        SessionKey key = new SessionKey(tenantId, conversationSessionId);
        Connection connection = new Connection(subscriber);
        connections.compute(
                key,
                (ignored, existing) -> {
                    CopyOnWriteArrayList<Connection> values =
                            existing == null ? new CopyOnWriteArrayList<>() : existing;
                    values.add(connection);
                    if (existing == null) {
                        lifecycle.subscribe(tenantId, conversationSessionId);
                    }
                    return values;
                });
        return new Registration(connection, () -> remove(key, connection));
    }

    /** owner 本地投递和 Redis 入站消息共用同一连接级排序规则。 */
    public void publish(AgentExecutionDeltaNotification envelope) {
        CopyOnWriteArrayList<Connection> sessionConnections =
                connections.get(
                        new SessionKey(envelope.tenantId(), envelope.conversationSessionId()));
        if (sessionConnections != null) {
            sessionConnections.forEach(connection -> connection.deliver(envelope));
        }
    }

    public boolean hasConnections(String tenantId, String conversationSessionId) {
        CopyOnWriteArrayList<Connection> values =
                connections.get(new SessionKey(tenantId, conversationSessionId));
        return values != null && !values.isEmpty();
    }

    private void remove(SessionKey key, Connection connection) {
        connections.computeIfPresent(
                key,
                (ignored, values) -> {
                    values.remove(connection);
                    if (values.isEmpty()) {
                        lifecycle.unsubscribe(key.tenantId(), key.conversationSessionId());
                        return null;
                    }
                    return values;
                });
    }

    public interface Subscriber {
        void onDelta(AgentExecutionDeltaNotification envelope);

        void onGap(AgentExecutionDeltaNotification envelope);
    }

    public interface ChannelLifecycle {
        default void subscribe(String tenantId, String conversationSessionId) {}

        default void unsubscribe(String tenantId, String conversationSessionId) {}
    }

    public static final class Registration implements AutoCloseable {

        private final Runnable closeAction;
        private final Connection connection;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Registration(Connection connection, Runnable closeAction) {
            this.connection = connection;
            this.closeAction = closeAction;
        }

        public void resume(
                String executionId,
                long executionAttemptNumber,
                long modelInvocationSequence,
                long outputDeltaSequence) {
            connection.resume(
                    executionId,
                    executionAttemptNumber,
                    modelInvocationSequence,
                    outputDeltaSequence);
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

        private synchronized void resume(
                String executionId,
                long executionAttemptNumber,
                long modelInvocationSequence,
                long outputDeltaSequence) {
            cursors.put(
                    executionId,
                    new Cursor(
                            executionAttemptNumber, modelInvocationSequence, outputDeltaSequence));
        }

        private synchronized void deliver(AgentExecutionDeltaNotification envelope) {
            Cursor current = cursors.get(envelope.executionId());
            Cursor incoming =
                    new Cursor(
                            envelope.executionAttemptNumber(),
                            envelope.modelInvocationSequence(),
                            envelope.outputDeltaSequence());
            if (current == null || incoming.isNewProjectionAfter(current)) {
                if (incoming.outputDeltaSequence() == 1) {
                    cursors.put(envelope.executionId(), incoming);
                    subscriber.onDelta(envelope);
                } else {
                    subscriber.onGap(envelope);
                }
                return;
            }
            if (incoming.isOlderProjectionThan(current)
                    || incoming.outputDeltaSequence() <= current.outputDeltaSequence()) {
                return;
            }
            if (incoming.outputDeltaSequence() == current.outputDeltaSequence() + 1) {
                cursors.put(envelope.executionId(), incoming);
                subscriber.onDelta(envelope);
            } else {
                subscriber.onGap(envelope);
            }
        }
    }

    private record SessionKey(String tenantId, String conversationSessionId) {}

    private record Cursor(
            long executionAttemptNumber, long modelInvocationSequence, long outputDeltaSequence) {

        private boolean isNewProjectionAfter(Cursor other) {
            return executionAttemptNumber > other.executionAttemptNumber
                    || (executionAttemptNumber == other.executionAttemptNumber
                            && modelInvocationSequence > other.modelInvocationSequence);
        }

        private boolean isOlderProjectionThan(Cursor other) {
            return executionAttemptNumber < other.executionAttemptNumber
                    || (executionAttemptNumber == other.executionAttemptNumber
                            && modelInvocationSequence < other.modelInvocationSequence);
        }
    }
}
