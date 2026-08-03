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

    /** Redis bridge 尚未装配时使用的无操作 channel 生命周期。 */
    private static final ChannelLifecycle NOOP_LIFECYCLE = new ChannelLifecycle() {};

    /** tenant/session 到本实例 SSE 连接集合的并发映射。 */
    private final ConcurrentMap<SessionKey, CopyOnWriteArrayList<Connection>> connections =
            new ConcurrentHashMap<>();

    /** 首连接/末连接变化时负责订阅或取消 Redis channel 的回调。 */
    private volatile ChannelLifecycle lifecycle = NOOP_LIFECYCLE;

    /** 将 Redis bridge 绑定为本 registry 的 channel 生命周期实现。 */
    public void attachLifecycle(ChannelLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /** 首连接触发 Redis 订阅，末连接关闭时取消对应 channel。 */
    public Registration register(
            String tenantId, String conversationSessionId, Subscriber subscriber) {
        // 每个注册创建独立连接游标，避免同一会话的多个浏览器互相推进位置。
        SessionKey key = new SessionKey(tenantId, conversationSessionId);
        Connection connection = new Connection(subscriber);

        // compute 原子完成连接加入与首连接 Redis 订阅，避免并发漏订阅。
        connections.compute(
                key,
                (ignored, existing) -> {
                    CopyOnWriteArrayList<Connection> values =
                            existing == null ? new CopyOnWriteArrayList<>() : existing;
                    values.add(connection);

                    // 原映射不存在意味着这是本实例该会话的首个连接。
                    if (existing == null) {
                        lifecycle.subscribe(tenantId, conversationSessionId);
                    }
                    return values;
                });

        // Registration 将幂等关闭语义封装给 SSE subscription。
        return new Registration(connection, () -> remove(key, connection));
    }

    /** owner 本地投递和 Redis 入站消息共用同一连接级排序规则。 */
    public void publish(AgentExecutionDeltaNotification envelope) {
        // tenant/session 精确路由，禁止跨租户或跨会话广播回答内容。
        CopyOnWriteArrayList<Connection> sessionConnections =
                connections.get(
                        new SessionKey(envelope.tenantId(), envelope.conversationSessionId()));

        // 没有本地连接时直接返回；跨实例投递由各自 registry 独立处理。
        if (sessionConnections != null) {
            sessionConnections.forEach(connection -> connection.deliver(envelope));
        }
    }

    /** 返回本实例是否保存指定 tenant/session 的有效 SSE 连接。 */
    public boolean hasConnections(String tenantId, String conversationSessionId) {
        CopyOnWriteArrayList<Connection> values =
                connections.get(new SessionKey(tenantId, conversationSessionId));
        return values != null && !values.isEmpty();
    }

    /** 移除单个连接，并在集合变空时取消对应 Redis channel。 */
    private void remove(SessionKey key, Connection connection) {
        // computeIfPresent 原子完成连接移除和末连接 channel 取消。
        connections.computeIfPresent(
                key,
                (ignored, values) -> {
                    values.remove(connection);

                    // 空集合不保留在 map 中，同时通知 bridge 释放 Redis 订阅。
                    if (values.isEmpty()) {
                        lifecycle.unsubscribe(key.tenantId(), key.conversationSessionId());
                        return null;
                    }
                    return values;
                });
    }

    /** 接收连接级连续增量或跳号通知。 */
    public interface Subscriber {

        /** 投递已经确认与当前游标连续的回答增量。 */
        void onDelta(AgentExecutionDeltaNotification envelope);

        /** 通知连接发现跳号或未知投影，需要从数据库 snapshot 重同步。 */
        void onGap(AgentExecutionDeltaNotification envelope);
    }

    /** 管理某 tenant/session 在外部消息总线上的订阅生命周期。 */
    public interface ChannelLifecycle {

        /** 本实例出现首连接时订阅对应 channel。 */
        default void subscribe(String tenantId, String conversationSessionId) {}

        /** 本实例关闭末连接时取消对应 channel。 */
        default void unsubscribe(String tenantId, String conversationSessionId) {}
    }

    /** 暴露给 SSE subscription 的幂等连接注册句柄。 */
    public static final class Registration implements AutoCloseable {

        /** 关闭时从 registry 移除连接的回调。 */
        private final Runnable closeAction;

        /** 当前注册对应的连接级流游标。 */
        private final Connection connection;

        /** 防止 servlet 多个完成回调重复执行移除逻辑。 */
        private final AtomicBoolean closed = new AtomicBoolean();

        /** 创建连接注册句柄。 */
        private Registration(Connection connection, Runnable closeAction) {
            this.connection = connection;
            this.closeAction = closeAction;
        }

        /** 使用数据库 snapshot 的权威流坐标恢复连接游标。 */
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

        /** 幂等关闭注册并触发连接移除。 */
        @Override
        public void close() {
            // 只有首次关闭执行 remove，后续容器回调直接忽略。
            if (closed.compareAndSet(false, true)) {
                closeAction.run();
            }
        }
    }

    /** 保存单个 SSE 连接的 subscriber 及按 execution 隔离的投递游标。 */
    private static final class Connection {

        /** 接收连续增量和跳号信号的 SSE 订阅者。 */
        private final Subscriber subscriber;

        /** executionId 到当前已投递流坐标的并发映射。 */
        private final ConcurrentMap<String, Cursor> cursors = new ConcurrentHashMap<>();

        /** 创建连接并校验 subscriber 不为空。 */
        private Connection(Subscriber subscriber) {
            this.subscriber = Objects.requireNonNull(subscriber, "subscriber");
        }

        /** 用权威 snapshot 覆盖指定 execution 的连接游标。 */
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

        /** 按 attempt、模型调用和 delta 序号过滤重复、旧投影及跳号消息。 */
        private synchronized void deliver(AgentExecutionDeltaNotification envelope) {
            // 当前游标按 execution 隔离，新入站通知转换为可比较的三段坐标。
            Cursor current = cursors.get(envelope.executionId());
            Cursor incoming =
                    new Cursor(
                            envelope.executionAttemptNumber(),
                            envelope.modelInvocationSequence(),
                            envelope.outputDeltaSequence());

            // 新 execution/projection 只有从 delta=1 开始才能直接建立连续投影。
            if (current == null || incoming.isNewProjectionAfter(current)) {
                // 新投影首条消息建立游标；从中间开始的消息只能报告 gap。
                if (incoming.outputDeltaSequence() == 1) {
                    cursors.put(envelope.executionId(), incoming);
                    subscriber.onDelta(envelope);
                } else {
                    subscriber.onGap(envelope);
                }
                return;
            }

            // 旧 attempt/模型调用以及已经投递的重复序号全部静默丢弃。
            if (incoming.isOlderProjectionThan(current)
                    || incoming.outputDeltaSequence() <= current.outputDeltaSequence()) {
                return;
            }

            // 精确相邻序号可以立即投递，其余情况交给 snapshot 重同步。
            if (incoming.outputDeltaSequence() == current.outputDeltaSequence() + 1) {
                cursors.put(envelope.executionId(), incoming);
                subscriber.onDelta(envelope);
            } else {
                subscriber.onGap(envelope);
            }
        }
    }

    /**
     * 本地连接映射键。
     *
     * @param tenantId 租户标识
     * @param conversationSessionId 会话标识
     */
    private record SessionKey(String tenantId, String conversationSessionId) {}

    /**
     * 单个 execution 在连接上的三段流坐标。
     *
     * @param executionAttemptNumber owner attempt
     * @param modelInvocationSequence 模型调用序号
     * @param outputDeltaSequence 已投递回答增量序号
     */
    private record Cursor(
            long executionAttemptNumber, long modelInvocationSequence, long outputDeltaSequence) {

        /** 判断当前坐标是否属于比给定坐标更新的 attempt 或模型调用。 */
        private boolean isNewProjectionAfter(Cursor other) {
            return executionAttemptNumber > other.executionAttemptNumber
                    || (executionAttemptNumber == other.executionAttemptNumber
                            && modelInvocationSequence > other.modelInvocationSequence);
        }

        /** 判断当前坐标是否属于比给定坐标更旧的 attempt 或模型调用。 */
        private boolean isOlderProjectionThan(Cursor other) {
            return executionAttemptNumber < other.executionAttemptNumber
                    || (executionAttemptNumber == other.executionAttemptNumber
                            && modelInvocationSequence < other.modelInvocationSequence);
        }
    }
}
