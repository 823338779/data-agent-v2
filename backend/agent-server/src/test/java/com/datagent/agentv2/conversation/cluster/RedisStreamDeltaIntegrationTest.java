package com.datagent.agentv2.conversation.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.stream.LocalSseRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

class RedisStreamDeltaIntegrationTest {

    private static final String TENANT_ID = "tenant_stream";
    private static final String SESSION_ID = "session_stream";
    private static final String EXECUTION_ID = "execution_stream";
    private static final String REDIS_PASSWORD = "redis_test";
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withCommand("redis-server", "--requirepass", REDIS_PASSWORD)
                    .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @Test
    void forwardsAcrossInstancesAndTurnsDuplicateOrGapIntoSafeDeliveryState() {
        try (TestInstance browserInstance = instance();
                TestInstance ownerInstance = instance()) {
            CopyOnWriteArrayList<AgentExecutionDeltaNotification> delivered =
                    new CopyOnWriteArrayList<>();
            CopyOnWriteArrayList<AgentExecutionDeltaNotification> gaps =
                    new CopyOnWriteArrayList<>();
            LocalSseRegistry.Registration registration =
                    browserInstance
                            .registry()
                            .register(
                                    TENANT_ID,
                                    SESSION_ID,
                                    new LocalSseRegistry.Subscriber() {
                                        @Override
                                        public void onDelta(
                                                AgentExecutionDeltaNotification envelope) {
                                            delivered.add(envelope);
                                        }

                                        @Override
                                        public void onGap(
                                                AgentExecutionDeltaNotification envelope) {
                                            gaps.add(envelope);
                                        }
                                    });

            // 只有持有详细连接的实例订阅会话 channel，owner 经 Redis 把首个 delta 转发给它。
            assertThat(browserInstance.bridge().subscribedChannelCount()).isEqualTo(1);
            assertThat(ownerInstance.bridge().subscribedChannelCount()).isZero();
            AgentExecutionDeltaNotification first = delta(1, "a");
            assertThat(ownerInstance.bridge().publish(first)).isTrue();
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> assertThat(delivered).containsExactly(first));

            // 重复序号被连接级 tracker 忽略；跳号只触发 resync，不拼接错误正文。
            browserInstance.registry().publish(first);
            AgentExecutionDeltaNotification gap = delta(3, "c");
            browserInstance.registry().publish(gap);
            assertThat(delivered).containsExactly(first);
            assertThat(gaps).containsExactly(gap);

            registration.close();
            assertThat(browserInstance.bridge().subscribedChannelCount()).isZero();

            // Redis 中断后仍先完成 owner 本地投递，并把远端 publish 明确降级为 false。
            CopyOnWriteArrayList<AgentExecutionDeltaNotification> ownerLocal =
                    new CopyOnWriteArrayList<>();
            ownerInstance
                    .registry()
                    .register(
                            TENANT_ID,
                            SESSION_ID,
                            new LocalSseRegistry.Subscriber() {
                                @Override
                                public void onDelta(AgentExecutionDeltaNotification envelope) {
                                    ownerLocal.add(envelope);
                                }

                                @Override
                                public void onGap(AgentExecutionDeltaNotification envelope) {}
                            });
            REDIS.stop();
            assertThat(ownerInstance.bridge().publish(first)).isFalse();
            assertThat(ownerLocal).containsExactly(first);
        }
    }

    private static AgentExecutionDeltaNotification delta(long sequence, String content) {
        return new AgentExecutionDeltaNotification(
                AgentExecutionDeltaNotification.TYPE_STREAM_DELTA,
                TENANT_ID,
                SESSION_ID,
                EXECUTION_ID,
                1,
                1,
                sequence,
                AgentExecutionDeltaNotification.DELTA_REASONING,
                content,
                Instant.parse("2026-08-01T06:00:00Z"));
    }

    private static TestInstance instance() {
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        configuration.setUsername("default");
        configuration.setPassword(RedisPassword.of(REDIS_PASSWORD));
        LettuceConnectionFactory connections = new LettuceConnectionFactory(configuration);
        connections.afterPropertiesSet();
        connections.start();
        StringRedisTemplate redis = new StringRedisTemplate(connections);
        redis.afterPropertiesSet();
        LocalSseRegistry registry = new LocalSseRegistry();
        RedisExecutionDeltaBridge bridge =
                new RedisExecutionDeltaBridge(
                        redis, connections, JsonMapper.builder().build(), registry);
        bridge.start();
        return new TestInstance(connections, registry, bridge);
    }

    private record TestInstance(
            LettuceConnectionFactory connections,
            LocalSseRegistry registry,
            RedisExecutionDeltaBridge bridge)
            implements AutoCloseable {

        @Override
        public void close() {
            bridge.close();
            connections.destroy();
        }
    }
}
