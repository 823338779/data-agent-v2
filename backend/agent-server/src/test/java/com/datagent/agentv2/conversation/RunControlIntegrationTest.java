package com.datagent.agentv2.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datagent.agentv2.conversation.runtime.RunClaimService;
import com.datagent.agentv2.conversation.runtime.RunRecoveryScheduler;
import com.datagent.agentv2.platform.context.TenantContext;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "data-agent.runtime.scheduling-enabled=false")
@AutoConfigureMockMvc
class RunControlIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withCommand("redis-server", "--requirepass", "redis_test")
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
        registry.add("spring.data.redis.password", () -> "redis_test");
    }

    @AfterAll
    static void stopContainers() {
        REDIS.stop();
        POSTGRES.stop();
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private RunClaimService claims;
    @Autowired private RunRecoveryScheduler recovery;

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
    void queuedCancellationIsImmediateAndIdempotent() throws Exception {
        Fixture fixture = queued("cancel-queued");

        cancel(fixture).andExpect(jsonPath("$.status").value("cancelled"));
        cancel(fixture).andExpect(jsonPath("$.status").value("cancelled"));

        assertThat(outboxCount(fixture.runId())).isZero();
    }

    @Test
    void runningCancellationWritesOneFixedControlNotification() throws Exception {
        Fixture fixture = queued("cancel-running");
        makeRunning(fixture.runId());

        cancel(fixture)
                .andExpect(jsonPath("$.status").value("running"))
                .andExpect(jsonPath("$.cancellationRequestedAt").isNotEmpty());
        cancel(fixture).andExpect(status().isAccepted());

        assertThat(outboxCount(fixture.runId())).isEqualTo(1);
        String payload =
                jdbc.queryForObject(
                        "select payload::text from notification_outbox where aggregate_id=?",
                        String.class,
                        fixture.runId());
        JsonNode node = json.readTree(payload);
        assertThat(node.path("notificationId").asString()).isNotBlank();
        assertThat(node.path("tenantId").asString()).isEqualTo("tenant_local");
        assertThat(node.path("runId").asString()).isEqualTo(fixture.runId());
        assertThat(node.path("ownerInstanceId").asString()).isEqualTo("instance_control");
        assertThat(node.path("executionAttempt").asLong()).isEqualTo(1L);
        assertThat(node.path("controlVersion").asLong()).isPositive();
        assertThat(node.path("requestedAt").asString()).isNotBlank();
        assertThat(node.path("type").asString()).isEqualTo("cancel");
    }

    @Test
    void ownerPollingFinalizesRunningCancellationAndReleasesCapacity() throws Exception {
        Fixture fixture = queued("cancel-owned");
        assertThat(TenantContext.call("tenant_local", claims::claimNext)).isTrue();
        cancel(fixture).andExpect(jsonPath("$.status").value("running"));

        recovery.pollRunControlsNow();

        assertThat(
                        jdbc.queryForObject(
                                "select status from agent_run where uuid=?",
                                String.class,
                                fixture.runId()))
                .isEqualTo("cancelled");
        assertThat(
                        jdbc.queryForObject(
                                "select status from agent_run_attempt where run_id=?",
                                String.class,
                                fixture.runId()))
                .isEqualTo("cancelled");
    }

    @Test
    void terminalRunRetryIsIdempotentAndCreatesQueuedSuccessor() throws Exception {
        Fixture fixture = queued("retry");
        cancel(fixture);

        String first = retry(fixture, "retry-key");
        String replay = retry(fixture, "retry-key");
        assertThat(json.readTree(first).path("id").asString())
                .isEqualTo(json.readTree(replay).path("id").asString());
        assertThat(json.readTree(first).path("status").asString()).isEqualTo("queued");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from agent_run where session_id=?",
                                Integer.class,
                                fixture.sessionId()))
                .isEqualTo(2);
    }

    private Fixture queued(String suffix) throws Exception {
        Cookie login = login();
        String session = createSession(login, suffix);
        String body =
                mvc.perform(
                                post("/api/v1/agent-sessions/{sessionId}/messages", session)
                                        .cookie(login)
                                        .header("Idempotency-Key", "submit-" + suffix)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"content\":\"hello\"}"))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return new Fixture(login, session, json.readTree(body).path("run").path("id").asString());
    }

    private org.springframework.test.web.servlet.ResultActions cancel(Fixture fixture)
            throws Exception {
        return mvc.perform(
                        post(
                                        "/api/v1/agent-sessions/{sessionId}/runs/{runId}:cancel",
                                        fixture.sessionId(),
                                        fixture.runId())
                                .cookie(fixture.login()))
                .andExpect(status().isAccepted());
    }

    private String retry(Fixture fixture, String key) throws Exception {
        return mvc.perform(
                        post(
                                        "/api/v1/agent-sessions/{sessionId}/runs/{runId}:retry",
                                        fixture.sessionId(),
                                        fixture.runId())
                                .cookie(fixture.login())
                                .header("Idempotency-Key", key))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private void makeRunning(String runId) {
        Instant now = Instant.now();
        jdbc.update(
                """
                update agent_run set status='running', phase='RECEIVED', execution_attempt=1,
                  owner_instance_id='instance_control', started_at=?, heartbeat_at=?,
                  lease_expires_at=?, active_segment_started_at=?, hard_deadline_at=?
                where uuid=?
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(60)),
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(600)),
                runId);
        jdbc.update(
                "insert into agent_run_attempt(uuid, tenant_id, run_id, execution_attempt, owner_instance_id, status, started_at) values (?, 'tenant_local', ?, 1, 'instance_control', 'running', ?)",
                "attempt_control_" + runId,
                runId,
                Timestamp.from(now));
    }

    private int outboxCount(String runId) {
        return jdbc.queryForObject(
                "select count(*) from notification_outbox where aggregate_id=?",
                Integer.class,
                runId);
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

    private record Fixture(Cookie login, String sessionId, String runId) {}
}
