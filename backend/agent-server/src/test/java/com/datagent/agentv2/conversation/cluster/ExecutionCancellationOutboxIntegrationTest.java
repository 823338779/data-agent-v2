package com.datagent.agentv2.conversation.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datagent.agentv2.conversation.runtime.AgentExecutionLeaseService;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.conversation.runtime.ExecutionWorkerIdentity;
import com.datagent.agentv2.platform.context.TenantContext;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "data-agent.runtime.scheduling-enabled=false")
@AutoConfigureMockMvc
class ExecutionCancellationOutboxIntegrationTest {

    private static final String TENANT_ID = "tenant_local";
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
        registry.add("spring.data.redis.timeout", () -> "200ms");
        registry.add("spring.data.redis.connect-timeout", () -> "200ms");
    }

    @AfterAll
    static void stopContainers() {
        REDIS.stop();
        POSTGRES.stop();
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private ExecutionCancellationOutboxPublisher publisher;
    @Autowired private RedisExecutionCancellationListener listener;
    @Autowired private AgentExecutionLeaseService leases;
    @Autowired private ExecutionWorkerIdentity identity;

    @BeforeEach
    void clearExecutions() {
        jdbc.update("delete from notification_outbox_message");
        jdbc.update("delete from agent_execution_event");
        jdbc.update("delete from agent_execution_stream_checkpoint");
        jdbc.update("delete from agent_execution_attempt");
        jdbc.update("update conversation_message set execution_id = null");
        jdbc.update("delete from agent_execution");
        jdbc.update("delete from conversation_message");
        jdbc.update("delete from conversation_session");
    }

    @Test
    void publishesOnlyControlNotificationsAndDatabasePollingSurvivesRedisLoss() throws Exception {
        Fixture delivered = createRunningThenCancelledExecution("published");
        insertUnrelatedOutbox();

        // 成功发布后只标记 control outbox；共享 listener 由数据库 owner fencing 完成取消。
        assertThat(publisher.publishDueNow()).isEqualTo(1);
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(3))
                .untilAsserted(
                        () ->
                                assertThat(executionStatus(delivered.executionId()))
                                        .isEqualTo("cancelled"));
        assertThat(publishedAt(delivered.executionId())).isNotNull();
        assertThat(unrelatedPublishedAt()).isNull();
        AgentExecutionCancellationSignal deliveredEnvelope = envelope(delivered.executionId());
        assertThat(listener.handle(deliveredEnvelope)).isFalse();
        assertThat(
                        listener.handle(
                                new AgentExecutionCancellationSignal(
                                        "notification_wrong_owner",
                                        TENANT_ID,
                                        delivered.executionId(),
                                        "another_instance",
                                        1,
                                        deliveredEnvelope.controlVersion(),
                                        deliveredEnvelope.requestedAt(),
                                        deliveredEnvelope.type())))
                .isFalse();

        // Redis 丢失时 publisher 只记录退避；owner 的数据库检查仍能完成取消。
        Fixture fallback = createRunningThenCancelledExecution("fallback");
        REDIS.stop();
        assertThat(publisher.publishDueNow()).isZero();
        assertThat(publishedAt(fallback.executionId())).isNull();
        assertThat(attemptCount(fallback.executionId())).isEqualTo(1);
        AgentExecutionPort.ClaimedAgentExecution owned =
                new AgentExecutionPort.ClaimedAgentExecution(
                        TENANT_ID, fallback.executionId(), identity.workerInstanceId(), 1);
        assertThat(TenantContext.call(TENANT_ID, () -> leases.cancelIfRequested(owned))).isTrue();
        assertThat(executionStatus(fallback.executionId())).isEqualTo("cancelled");
    }

    private Fixture createRunningThenCancelledExecution(String suffix) throws Exception {
        Cookie login = login();
        String conversationSessionId = createSession(login, suffix);
        String body =
                mvc.perform(
                                post(
                                                "/api/v1/agent-sessions/{conversationSessionId}/messages",
                                                conversationSessionId)
                                        .cookie(login)
                                        .header("Idempotency-Key", "submit-" + suffix)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"content\":\"hello\"}"))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String executionId = json.readTree(body).path("execution").path("executionId").asString();
        Instant now = Instant.now();
        jdbc.update(
                """
                update agent_execution set status='running', phase='RECEIVED', execution_attempt_number=1,
                  owner_instance_id=?, started_at=?, heartbeat_at=?, lease_expires_at=?,
                  active_segment_started_at=?, hard_deadline_at=? where execution_id=?
                """,
                identity.workerInstanceId(),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(60)),
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(600)),
                executionId);
        jdbc.update(
                "insert into agent_execution_attempt(execution_attempt_id, tenant_id, execution_id, execution_attempt_number, owner_instance_id, status, started_at) values (?, ?, ?, 1, ?, 'running', ?)",
                "attempt_" + suffix,
                TENANT_ID,
                executionId,
                identity.workerInstanceId(),
                Timestamp.from(now));
        mvc.perform(
                        post(
                                        "/api/v1/agent-sessions/{conversationSessionId}/executions/{executionId}:cancel",
                                        conversationSessionId,
                                        executionId)
                                .cookie(login))
                .andExpect(status().isAccepted());
        return new Fixture(executionId);
    }

    private void insertUnrelatedOutbox() {
        jdbc.update(
                """
                insert into notification_outbox_message(
                  notification_message_id, tenant_id, notification_type, aggregate_type, aggregate_id,
                  aggregate_version, payload, next_attempt_at)
                values ('notification_unrelated', ?, 'queue_changed', 'agent_execution',
                  'execution_unrelated', 1, '{}'::jsonb, current_timestamp)
                """,
                TENANT_ID);
    }

    private String executionStatus(String executionId) {
        return jdbc.queryForObject(
                "select status from agent_execution where execution_id=?",
                String.class,
                executionId);
    }

    private Instant publishedAt(String executionId) {
        return jdbc.queryForObject(
                "select published_at from notification_outbox_message where aggregate_id=?",
                Instant.class,
                executionId);
    }

    private Instant unrelatedPublishedAt() {
        return jdbc.queryForObject(
                "select published_at from notification_outbox_message where notification_message_id='notification_unrelated'",
                Instant.class);
    }

    private int attemptCount(String executionId) {
        return jdbc.queryForObject(
                "select attempt_count from notification_outbox_message where aggregate_id=?",
                Integer.class,
                executionId);
    }

    private AgentExecutionCancellationSignal envelope(String executionId) throws Exception {
        String payload =
                jdbc.queryForObject(
                        "select payload::text from notification_outbox_message where aggregate_id=?",
                        String.class,
                        executionId);
        return json.readValue(payload, AgentExecutionCancellationSignal.class);
    }

    private String createSession(Cookie login, String title) throws Exception {
        String body =
                mvc.perform(
                                post("/api/v1/agent-sessions")
                                        .cookie(login)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"title\":\"" + title + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.readTree(body).path("conversationSessionId").asString();
    }

    private Cookie login() throws Exception {
        String setCookie =
                mvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                        .andExpect(status().isNoContent())
                        .andReturn()
                        .getResponse()
                        .getHeader(HttpHeaders.SET_COOKIE);
        return new Cookie(
                "DATA_AGENT_SESSION",
                setCookie.substring("DATA_AGENT_SESSION=".length(), setCookie.indexOf(';')));
    }

    private record Fixture(String executionId) {}
}
