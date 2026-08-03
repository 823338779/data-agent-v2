package com.datagent.agentv2.conversation.cluster;

import com.datagent.agentv2.conversation.stream.LocalSseRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

/** 在本地详细 SSE registry 与按会话 Redis Pub/Sub channel 之间转发瞬时 delta。 */
@Component
public final class RedisExecutionDeltaBridge
        implements LocalSseRegistry.ChannelLifecycle, AutoCloseable {

    /** 会话流 Redis Pub/Sub channel 的固定前缀。 */
    public static final String STREAM_CHANNEL_PREFIX = "agent:stream:";

    /** channel 中租户与会话标识之间的分隔符。 */
    private static final String CHANNEL_SEPARATOR = ":";

    /** 发布回答增量 JSON 的 Redis 操作模板。 */
    private final StringRedisTemplate redis;

    /** 序列化和反序列化跨节点回答通知。 */
    private final ObjectMapper json;

    /** 保存本实例 SSE 连接并执行连接级去重。 */
    private final LocalSseRegistry registry;

    /** 动态维护按会话 channel 订阅的 Redis 监听容器。 */
    private final RedisMessageListenerContainer listenerContainer;

    /** channel 到监听器的映射，用于首订阅和末订阅生命周期管理。 */
    private final ConcurrentMap<String, MessageListener> listeners = new ConcurrentHashMap<>();

    /** 防止监听容器被重复启动或关闭。 */
    private final AtomicBoolean started = new AtomicBoolean();

    /** 创建 Redis bridge、配置连接工厂并接管本地 registry 的 channel 生命周期。 */
    public RedisExecutionDeltaBridge(
            StringRedisTemplate redis,
            RedisConnectionFactory connections,
            ObjectMapper json,
            LocalSseRegistry registry) {
        this.redis = redis;
        this.json = json;
        this.registry = registry;
        this.listenerContainer = new RedisMessageListenerContainer();
        this.listenerContainer.setConnectionFactory(connections);
        this.registry.attachLifecycle(this);
    }

    /** Spring 初始化完成后幂等启动 Redis 消息监听容器。 */
    @PostConstruct
    public void start() {
        // CAS 保证并发或重复生命周期回调只启动一次底层线程。
        if (started.compareAndSet(false, true)) {
            listenerContainer.afterPropertiesSet();
            listenerContainer.start();
        }
    }

    /** owner 先投递本实例，再 best-effort publish；Redis 故障不得中断 Agent 执行。 */
    public boolean publish(AgentExecutionDeltaNotification envelope) {
        // 本实例连接不依赖 Redis 回环，先获得最低延迟的本地增量。
        registry.publish(envelope);
        // Redis publish 是跨实例 best-effort 路径，异常不得回滚模型执行事务。
        try {
            // 跨实例消息按 tenant/session channel 隔离，并以 JSON 作为瞬时载荷。
            redis.convertAndSend(
                    channel(envelope.tenantId(), envelope.conversationSessionId()),
                    json.writeValueAsString(envelope));
            return true;
        } catch (RuntimeException failure) {
            // Redis 只承担加速通知；失败后的缺口由数据库 checkpoint 重同步修复。
            return false;
        }
    }

    /** 数据库检查点提交成功后再发布 delta，避免客户端观察到已回滚的瞬时状态。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void publishAfterCommit(AgentExecutionDeltaNotification envelope) {
        publish(envelope);
    }

    /** 为本实例某会话的首个 SSE 连接建立唯一 Redis channel 监听器。 */
    @Override
    public void subscribe(String tenantId, String conversationSessionId) {
        // 每个 channel 使用独立 listener，使末连接关闭时可以精确取消订阅。
        String channel = channel(tenantId, conversationSessionId);
        MessageListener listener = (message, ignored) -> receive(message.getBody());

        // putIfAbsent 保证并发首连接只向 Redis 注册一次监听器。
        if (listeners.putIfAbsent(channel, listener) == null) {
            // 容器注册可能因 Redis 故障失败，需要回滚 listeners 映射。
            try {
                listenerContainer.addMessageListener(listener, new ChannelTopic(channel));
            } catch (RuntimeException failure) {
                // 注册失败时回滚本地映射，后续连接仍可重新尝试订阅。
                listeners.remove(channel, listener);
            }
        }
    }

    /** 为本实例某会话的末个 SSE 连接移除 Redis channel 监听器。 */
    @Override
    public void unsubscribe(String tenantId, String conversationSessionId) {
        String channel = channel(tenantId, conversationSessionId);
        MessageListener listener = listeners.remove(channel);

        // 只有本地确实持有监听器时才调用容器移除，保持取消操作幂等。
        if (listener != null) {
            listenerContainer.removeMessageListener(listener, new ChannelTopic(channel));
        }
    }

    /** 返回本实例当前订阅的会话 channel 数量，供测试和运行观测使用。 */
    public int subscribedChannelCount() {
        return listeners.size();
    }

    /** 应用关闭时幂等停止监听线程、销毁容器并清空 channel 映射。 */
    @PreDestroy
    @Override
    public void close() {
        // CAS 保证底层容器的 stop/destroy 生命周期只执行一次。
        if (started.compareAndSet(true, false)) {
            listenerContainer.stop();
            // destroy 释放容器资源；stop 已完成后销毁失败不影响业务一致性。
            try {
                listenerContainer.destroy();
            } catch (Exception ignored) {
                // stop 已终止监听线程；销毁阶段不再影响运行时状态。
            }

            // 容器停止后清空内存映射，避免保留已经失效的监听器引用。
            listeners.clear();
        }
    }

    /** 解析 Redis 入站 JSON 并复用本地 registry 的连接级排序逻辑。 */
    private void receive(byte[] body) {
        // 非法载荷或新旧版本不兼容时直接丢弃，后续 snapshot 会修复缺口。
        try {
            // Redis message body 固定按 UTF-8 解码，再映射为强类型流坐标通知。
            AgentExecutionDeltaNotification envelope =
                    json.readValue(
                            new String(body, StandardCharsets.UTF_8),
                            AgentExecutionDeltaNotification.class);
            registry.publish(envelope);
        } catch (Exception ignored) {
            // 非法或不兼容消息只丢弃，checkpoint reconciliation 负责最终收敛。
        }
    }

    /** 根据租户和会话标识构造隔离的 Redis Pub/Sub channel。 */
    private static String channel(String tenantId, String conversationSessionId) {
        return STREAM_CHANNEL_PREFIX + tenantId + CHANNEL_SEPARATOR + conversationSessionId;
    }
}
