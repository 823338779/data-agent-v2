package com.datagent.agentv2.conversation.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datagent.agentv2.conversation.model.ModelDelta;
import com.datagent.agentv2.conversation.runtime.RunExecutionPort;
import com.datagent.agentv2.conversation.runtime.RunOwnershipLostException;
import com.datagent.agentv2.persistence.entity.AgentRunStreamEntity;
import com.datagent.agentv2.persistence.service.AgentRunStreamService;
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
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "data-agent.runtime.scheduling-enabled=false")
@AutoConfigureMockMvc
class RunStreamCheckpointIntegrationTest {

    private static final String TENANT_ID = "tenant_local";
    private static final String OWNER_ID = "instance_checkpoint";
    private static final String STALE_OWNER_ID = "old-owner";
    private static final String STALE_TEXT = "stale";
    private static final String PHASE_ANSWERING = "ANSWERING";
    private static final String REDIS_PASSWORD = "redis_test";
    private static final Instant STARTED_AT = Instant.parse("2026-08-01T06:00:00Z");
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

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private AgentRunStreamService streams;
    @Autowired private RunStreamCheckpointService checkpoints;

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
    void checkpointsAtSizeTimeAndTurnBoundariesWithoutAcceptingStaleProjection() throws Exception {
        RunExecutionPort.OwnedRun run = runningRun("checkpoint-thresholds");

        // 新 turn 从零开始；短增量先发布但尚未达到持久化阈值。
        TenantContext.run(TENANT_ID, () -> checkpoints.beginTurn(run, 1, STARTED_AT));
        RunStreamCheckpointService.PublishedDelta first =
                TenantContext.call(
                        TENANT_ID,
                        () -> checkpoints.append(run, 1, ModelDelta.reasoning("a"), STARTED_AT));
        assertThat(first.streamSequence()).isEqualTo(1);
        assertThat(first.checkpointed()).isFalse();
        assertStream(run.runId(), 1, 1, 0, "", "");

        // 累计达到 512 字符后写完整投影和最后已发布序号。
        String remainder = "b".repeat(511);
        RunStreamCheckpointService.PublishedDelta sized =
                TenantContext.call(
                        TENANT_ID,
                        () -> checkpoints.append(run, 1, ModelDelta.reasoning(remainder), STARTED_AT));
        assertThat(sized.streamSequence()).isEqualTo(2);
        assertThat(sized.checkpointed()).isTrue();
        assertStream(run.runId(), 1, 1, 2, "a" + remainder, "");
        long staleVersion = currentStream(run.runId()).getVersion();

        // reasoning 切到 answer 的 phase 边界先到，短增量也立即收敛。
        Instant phaseAt = STARTED_AT.plusMillis(1);
        RunStreamCheckpointService.PublishedDelta phased =
                TenantContext.call(
                        TENANT_ID,
                        () -> checkpoints.append(run, 1, ModelDelta.answer("z"), phaseAt));
        assertThat(phased.checkpointed()).isTrue();
        assertStream(run.runId(), 1, 1, 3, "a" + remainder, "z");

        // 距离上次 checkpoint 250ms 后，同 phase 的一个字符也立即收敛。
        Instant timedAt = phaseAt.plusMillis(250);
        RunStreamCheckpointService.PublishedDelta timed =
                TenantContext.call(
                        TENANT_ID,
                        () -> checkpoints.append(run, 1, ModelDelta.answer("q"), timedAt));
        assertThat(timed.checkpointed()).isTrue();
        assertStream(run.runId(), 1, 1, 4, "a" + remainder, "zq");
        assertThat(
                        TenantContext.call(
                                TENANT_ID,
                                () ->
                                        streams.checkpoint(
                                                run.runId(),
                                                staleVersion,
                                                1,
                                                1,
                                                OWNER_ID,
                                                5,
                                                STALE_TEXT,
                                                STALE_TEXT,
                                                PHASE_ANSWERING,
                                                timedAt.plusSeconds(60),
                                                timedAt.plusMillis(1))))
                .isFalse();
        assertThat(
                        TenantContext.call(
                                TENANT_ID,
                                () ->
                                        streams.checkpoint(
                                                run.runId(),
                                                currentStream(run.runId()).getVersion(),
                                                1,
                                                1,
                                                STALE_OWNER_ID,
                                                5,
                                                STALE_TEXT,
                                                STALE_TEXT,
                                                PHASE_ANSWERING,
                                                timedAt.plusSeconds(60),
                                                timedAt.plusMillis(1))))
                .isFalse();

        // 切换 turn 会清空当前投影和序号；旧 turn 随后的强制写入必须被 fencing 拒绝。
        TenantContext.run(TENANT_ID, () -> checkpoints.beginTurn(run, 2, timedAt.plusMillis(1)));
        TenantContext.run(
                TENANT_ID,
                () -> checkpoints.append(run, 2, ModelDelta.answer("new"), timedAt.plusMillis(2)));
        TenantContext.run(
                TENANT_ID,
                () ->
                        checkpoints.flush(
                                run,
                                2,
                                RunStreamCheckpointService.Boundary.TURN,
                                timedAt.plusMillis(3)));
        assertStream(run.runId(), 1, 2, 1, "", "new");
        TenantContext.run(
                TENANT_ID,
                () ->
                        checkpoints.append(
                                run, 2, ModelDelta.answer("!"), timedAt.plusMillis(4)));
        TenantContext.run(
                TENANT_ID,
                () ->
                        checkpoints.flush(
                                run,
                                2,
                                RunStreamCheckpointService.Boundary.LEASE_RELEASE,
                                timedAt.plusMillis(5)));
        assertStream(run.runId(), 1, 2, 2, "", "new!");
        assertThatThrownBy(
                        () ->
                                TenantContext.run(
                                        TENANT_ID,
                                        () ->
                                                checkpoints.flush(
                                                        run,
                                                        1,
                                                        RunStreamCheckpointService.Boundary.TERMINAL,
                                                        timedAt.plusMillis(6))))
                .isInstanceOf(RunOwnershipLostException.class);
    }

    private RunExecutionPort.OwnedRun runningRun(String suffix) throws Exception {
        Cookie login = login();
        String sessionId = createSession(login, suffix);
        String response =
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
        String runId = json.readTree(response).path("run").path("id").asString();
        jdbc.update(
                """
                update agent_run set status='running', phase='RECEIVED', execution_attempt=1,
                  owner_instance_id=?, started_at=?, heartbeat_at=?, lease_expires_at=?,
                  active_segment_started_at=?, hard_deadline_at=? where run_id=?
                """,
                OWNER_ID,
                Timestamp.from(STARTED_AT),
                Timestamp.from(STARTED_AT),
                Timestamp.from(STARTED_AT.plusSeconds(60)),
                Timestamp.from(STARTED_AT),
                Timestamp.from(STARTED_AT.plusSeconds(600)),
                runId);
        return new RunExecutionPort.OwnedRun(TENANT_ID, runId, OWNER_ID, 1);
    }

    private void assertStream(
            String runId, long attempt, long turn, long sequence, String reasoning, String answer) {
        AgentRunStreamEntity stream = currentStream(runId);
        assertThat(stream.getExecutionAttempt()).isEqualTo(attempt);
        assertThat(stream.getModelTurnSequence()).isEqualTo(turn);
        assertThat(stream.getStreamSequence()).isEqualTo(sequence);
        assertThat(stream.getReasoning()).isEqualTo(reasoning);
        assertThat(stream.getAnswer()).isEqualTo(answer);
    }

    private AgentRunStreamEntity currentStream(String runId) {
        return TenantContext.call(TENANT_ID, () -> streams.findByRunId(runId));
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
}
