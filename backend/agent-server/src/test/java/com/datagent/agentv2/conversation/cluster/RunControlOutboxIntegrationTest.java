package com.datagent.agentv2.conversation.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datagent.agentv2.conversation.runtime.RunExecutionPort;
import com.datagent.agentv2.conversation.runtime.RunInstanceIdentity;
import com.datagent.agentv2.conversation.runtime.RunLeaseService;
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
class RunControlOutboxIntegrationTest {

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
    @Autowired private RunControlOutboxPublisher publisher;
    @Autowired private RedisRunControlListener listener;
    @Autowired private RunLeaseService leases;
    @Autowired private RunInstanceIdentity identity;

    @BeforeEach
    void clearRuns() {
        jdbc.update("delete from notification_outbox");
        jdbc.update("delete from agent_run_event");
        jdbc.update("delete from agent_run_stream");
        jdbc.update("delete from agent_run_attempt");
        jdbc.update("update agent_message set run_id = null");
        jdbc.update("delete from agent_run");
        jdbc.update("delete from agent_message");
        jdbc.update("delete from agent_session");
    }

    @Test
    void publishesOnlyControlNotificationsAndDatabasePollingSurvivesRedisLoss() throws Exception {
        Fixture delivered = runningAndCancelled("published");
        insertUnrelatedOutbox();

        // 成功发布后只标记 control outbox；共享 listener 由数据库 owner fencing 完成取消。
        assertThat(publisher.publishDueNow()).isEqualTo(1);
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(3))
                .untilAsserted(
                        () -> assertThat(runStatus(delivered.runId())).isEqualTo("cancelled"));
        assertThat(publishedAt(delivered.runId())).isNotNull();
        assertThat(unrelatedPublishedAt()).isNull();
        RunControlEnvelope deliveredEnvelope = envelope(delivered.runId());
        assertThat(listener.handle(deliveredEnvelope)).isFalse();
        assertThat(
                        listener.handle(
                                new RunControlEnvelope(
                                        "notification_wrong_owner",
                                        TENANT_ID,
                                        delivered.runId(),
                                        "another_instance",
                                        1,
                                        deliveredEnvelope.controlVersion(),
                                        deliveredEnvelope.requestedAt(),
                                        deliveredEnvelope.type())))
                .isFalse();

        // Redis 丢失时 publisher 只记录退避；owner 的数据库检查仍能完成取消。
        Fixture fallback = runningAndCancelled("fallback");
        REDIS.stop();
        assertThat(publisher.publishDueNow()).isZero();
        assertThat(publishedAt(fallback.runId())).isNull();
        assertThat(attemptCount(fallback.runId())).isEqualTo(1);
        RunExecutionPort.OwnedRun owned =
                new RunExecutionPort.OwnedRun(TENANT_ID, fallback.runId(), identity.id(), 1);
        assertThat(TenantContext.call(TENANT_ID, () -> leases.cancelIfRequested(owned))).isTrue();
        assertThat(runStatus(fallback.runId())).isEqualTo("cancelled");
    }

    private Fixture runningAndCancelled(String suffix) throws Exception {
        Cookie login = login();
        String sessionId = createSession(login, suffix);
        String body =
                mvc.perform(
                                post("/api/v1/agent-sessions/{sessionId}/messages", sessionId)
                                        .cookie(login)
                                        .header("Idempotency-Key", "submit-" + suffix)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"content\":\"hello\"}"))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String runId = json.readTree(body).path("run").path("id").asString();
        Instant now = Instant.now();
        jdbc.update(
                """
                update agent_run set status='running', phase='RECEIVED', execution_attempt=1,
                  owner_instance_id=?, started_at=?, heartbeat_at=?, lease_expires_at=?,
                  active_segment_started_at=?, hard_deadline_at=? where run_id=?
                """,
                identity.id(),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(60)),
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(600)),
                runId);
        jdbc.update(
                "insert into agent_run_attempt(attempt_id, tenant_id, run_id, execution_attempt, owner_instance_id, status, started_at) values (?, ?, ?, 1, ?, 'running', ?)",
                "attempt_" + suffix,
                TENANT_ID,
                runId,
                identity.id(),
                Timestamp.from(now));
        mvc.perform(
                        post(
                                        "/api/v1/agent-sessions/{sessionId}/runs/{runId}:cancel",
                                        sessionId,
                                        runId)
                                .cookie(login))
                .andExpect(status().isAccepted());
        return new Fixture(runId);
    }

    private void insertUnrelatedOutbox() {
        jdbc.update(
                """
                insert into notification_outbox(
                  notification_id, tenant_id, notification_type, aggregate_type, aggregate_id,
                  aggregate_version, payload, next_attempt_at)
                values ('notification_unrelated', ?, 'queue_changed', 'agent_run',
                  'run_unrelated', 1, '{}'::jsonb, current_timestamp)
                """,
                TENANT_ID);
    }

    private String runStatus(String runId) {
        return jdbc.queryForObject(
                "select status from agent_run where run_id=?", String.class, runId);
    }

    private Instant publishedAt(String runId) {
        return jdbc.queryForObject(
                "select published_at from notification_outbox where aggregate_id=?",
                Instant.class,
                runId);
    }

    private Instant unrelatedPublishedAt() {
        return jdbc.queryForObject(
                "select published_at from notification_outbox where notification_id='notification_unrelated'",
                Instant.class);
    }

    private int attemptCount(String runId) {
        return jdbc.queryForObject(
                "select attempt_count from notification_outbox where aggregate_id=?",
                Integer.class,
                runId);
    }

    private RunControlEnvelope envelope(String runId) throws Exception {
        String payload =
                jdbc.queryForObject(
                        "select payload::text from notification_outbox where aggregate_id=?",
                        String.class,
                        runId);
        return json.readValue(payload, RunControlEnvelope.class);
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
        return json.readTree(body).path("id").asString();
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

    private record Fixture(String runId) {}
}
