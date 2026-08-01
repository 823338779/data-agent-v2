package com.datagent.agentv2.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.conversation.retention.ReasoningRetentionJob;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
        properties = {
            "data-agent.runtime.scheduling-enabled=false",
            "data-agent.reasoning-retention.batch-size=2"
        })
class ReasoningRetentionIntegrationTest {

    private static final String REDIS_PASSWORD = "redis_test";
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withCommand("redis-server", "--requirepass", REDIS_PASSWORD)
                    .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.username", () -> "default");
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
    }

    @AfterAll
    static void stopContainers() {
        REDIS.stop();
        POSTGRES.stop();
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReasoningRetentionJob retention;
    @Autowired private MeterRegistry meters;

    @BeforeEach
    void insertExpiredAndProtectedReasoning() {
        // 三组执行分别覆盖已到期终态、未到期终态和已到期运行态。
        jdbc.execute(
                """
                insert into conversation_session(
                    conversation_session_id, tenant_id, title, session_type, created_by_user_id)
                values ('retention_session', 'tenant_local', 'Retention', 'INTERACTIVE', 'user_admin');
                insert into conversation_message(
                    conversation_message_id, tenant_id, conversation_session_id, role, content,
                    conversation_message_sequence, author_user_id)
                values ('retention_message', 'tenant_local', 'retention_session', 'user', 'keep', 1,
                        'user_admin');
                insert into agent_execution(
                    execution_id, tenant_id, conversation_session_id,
                    triggering_conversation_message_id, status, phase, execution_attempt_number,
                    owner_user_id, request_id, completed_at, owner_instance_id, started_at,
                    hard_deadline_at, active_segment_started_at, heartbeat_at, lease_expires_at)
                values
                    ('execution_expired', 'tenant_local', 'retention_session', 'retention_message',
                     'done', 'COMPLETED', 1, 'user_admin', 'request_expired', current_timestamp,
                     null, null, null, null, null, null),
                    ('execution_future', 'tenant_local', 'retention_session', 'retention_message',
                     'done', 'COMPLETED', 1, 'user_admin', 'request_future', current_timestamp,
                     null, null, null, null, null, null),
                    ('execution_running', 'tenant_local', 'retention_session', 'retention_message',
                     'running', 'THINKING', 1, 'user_admin', 'request_running', null,
                     'instance_retention', current_timestamp,
                     current_timestamp + interval '10 minutes', current_timestamp,
                     current_timestamp, current_timestamp + interval '1 minute');
                insert into agent_execution_stream_checkpoint(
                    execution_stream_checkpoint_id, execution_id, tenant_id,
                    execution_attempt_number, model_invocation_sequence, phase,
                    output_delta_sequence, reasoning, answer, reasoning_expires_at)
                values
                    ('stream_expired', 'execution_expired', 'tenant_local', 1, 1, 'COMPLETED', 1,
                     'expired stream reasoning', 'keep expired answer', current_timestamp - interval '1 day'),
                    ('stream_future', 'execution_future', 'tenant_local', 1, 1, 'COMPLETED', 1,
                     'future stream reasoning', 'keep future answer', current_timestamp + interval '1 day'),
                    ('stream_running', 'execution_running', 'tenant_local', 1, 1, 'THINKING', 1,
                     'running stream reasoning', '', current_timestamp - interval '1 day');
                insert into model_invocation(
                    model_invocation_id, execution_id, tenant_id, model_invocation_sequence,
                    execution_attempt_number, input_up_to_conversation_message_sequence, status,
                    reasoning, reasoning_expires_at, completed_at)
                values
                    ('invocation_expired', 'execution_expired', 'tenant_local', 1, 1, 1,
                     'completed', 'expired invocation reasoning', current_timestamp - interval '1 day', current_timestamp),
                    ('invocation_future', 'execution_future', 'tenant_local', 1, 1, 1,
                     'completed', 'future invocation reasoning', current_timestamp + interval '1 day', current_timestamp),
                    ('invocation_running', 'execution_running', 'tenant_local', 1, 1, 1,
                     'streaming', 'running invocation reasoning', current_timestamp - interval '1 day', null);
                """);
    }

    @Test
    void clearsOnlyExpiredTerminalReasoningAndPreservesOtherPayloads() {
        retention.runOnce(Instant.now());

        Map<String, Object> expired =
                jdbc.queryForMap(
                        "select reasoning, answer, phase from agent_execution_stream_checkpoint where execution_id = 'execution_expired'");
        assertThat(expired.get("reasoning")).isEqualTo("");
        assertThat(expired.get("answer")).isEqualTo("keep expired answer");
        assertThat(expired.get("phase")).isEqualTo("COMPLETED");
        assertThat(reasoning("execution_future", "agent_execution_stream_checkpoint"))
                .isEqualTo("future stream reasoning");
        assertThat(reasoning("execution_running", "agent_execution_stream_checkpoint"))
                .isEqualTo("running stream reasoning");
        assertThat(reasoning("execution_expired", "model_invocation")).isEqualTo("");
        assertThat(reasoning("execution_future", "model_invocation"))
                .isEqualTo("future invocation reasoning");
        assertThat(reasoning("execution_running", "model_invocation"))
                .isEqualTo("running invocation reasoning");
        assertThat(meters.counter("data_agent.reasoning_retention.cleaned").count())
                .isGreaterThanOrEqualTo(2.0);
    }

    private String reasoning(String executionId, String table) {
        return jdbc.queryForObject(
                "select reasoning from " + table + " where execution_id = ?",
                String.class,
                executionId);
    }
}
