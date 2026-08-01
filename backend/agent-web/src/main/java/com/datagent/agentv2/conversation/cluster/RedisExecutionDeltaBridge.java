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
import tools.jackson.databind.ObjectMapper;

/** 在本地详细 SSE registry 与按会话 Redis Pub/Sub channel 之间转发瞬时 delta。 */
@Component
public final class RedisExecutionDeltaBridge
        implements LocalSseRegistry.ChannelLifecycle, AutoCloseable {

    public static final String STREAM_CHANNEL_PREFIX = "agent:stream:";
    private static final String CHANNEL_SEPARATOR = ":";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final LocalSseRegistry registry;
    private final RedisMessageListenerContainer listenerContainer;
    private final ConcurrentMap<String, MessageListener> listeners = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();

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

    @PostConstruct
    public void start() {
        if (started.compareAndSet(false, true)) {
            listenerContainer.afterPropertiesSet();
            listenerContainer.start();
        }
    }

    /** owner 先投递本实例，再 best-effort publish；Redis 故障不得中断 Agent 执行。 */
    public boolean publish(AgentExecutionDeltaNotification envelope) {
        registry.publish(envelope);
        try {
            redis.convertAndSend(
                    channel(envelope.tenantId(), envelope.conversationSessionId()),
                    json.writeValueAsString(envelope));
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    @Override
    public void subscribe(String tenantId, String conversationSessionId) {
        String channel = channel(tenantId, conversationSessionId);
        MessageListener listener = (message, ignored) -> receive(message.getBody());
        if (listeners.putIfAbsent(channel, listener) == null) {
            try {
                listenerContainer.addMessageListener(listener, new ChannelTopic(channel));
            } catch (RuntimeException failure) {
                listeners.remove(channel, listener);
            }
        }
    }

    @Override
    public void unsubscribe(String tenantId, String conversationSessionId) {
        String channel = channel(tenantId, conversationSessionId);
        MessageListener listener = listeners.remove(channel);
        if (listener != null) {
            listenerContainer.removeMessageListener(listener, new ChannelTopic(channel));
        }
    }

    public int subscribedChannelCount() {
        return listeners.size();
    }

    @PreDestroy
    @Override
    public void close() {
        if (started.compareAndSet(true, false)) {
            listenerContainer.stop();
            try {
                listenerContainer.destroy();
            } catch (Exception ignored) {
                // stop 已终止监听线程；销毁阶段不再影响运行时状态。
            }
            listeners.clear();
        }
    }

    private void receive(byte[] body) {
        try {
            AgentExecutionDeltaNotification envelope =
                    json.readValue(
                            new String(body, StandardCharsets.UTF_8),
                            AgentExecutionDeltaNotification.class);
            registry.publish(envelope);
        } catch (Exception ignored) {
            // 非法或不兼容消息只丢弃，checkpoint reconciliation 负责最终收敛。
        }
    }

    private static String channel(String tenantId, String conversationSessionId) {
        return STREAM_CHANNEL_PREFIX + tenantId + CHANNEL_SEPARATOR + conversationSessionId;
    }
}
