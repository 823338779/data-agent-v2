package com.datagent.agentv2.conversation.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datagent.agentv2.conversation.domain.AgentExecutionStreamCheckpoint;
import com.datagent.agentv2.conversation.model.ModelOutputDelta;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.conversation.runtime.ExecutionOwnershipLostException;
import com.datagent.agentv2.persistence.service.AgentExecutionStreamCheckpointService;
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
class AgentExecutionStreamCheckpointIntegrationTest {

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
    @Autowired private AgentExecutionStreamCheckpointService streamCheckpoints;
    @Autowired private ExecutionStreamCheckpointCoordinator checkpoints;

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
    void checkpointsAtSizeTimeAndTurnBoundariesWithoutAcceptingStaleProjection() throws Exception {
        AgentExecutionPort.ClaimedAgentExecution run =
                createRunningExecution("checkpoint-thresholds");

        // 新 turn 从零开始；短增量先发布但尚未达到持久化阈值。
        TenantContext.run(TENANT_ID, () -> checkpoints.beginModelInvocation(run, 1, STARTED_AT));
        ExecutionStreamCheckpointCoordinator.PublishedDelta first =
                TenantContext.call(
                        TENANT_ID,
                        () ->
                                checkpoints.append(
                                        run, 1, ModelOutputDelta.reasoning("a"), STARTED_AT));
        assertThat(first.outputDeltaSequence()).isEqualTo(1);
        assertThat(first.checkpointed()).isFalse();
        assertStream(run.executionId(), 1, 1, 0, "", "");

        // 累计达到 512 字符后写完整投影和最后已发布序号。
        String remainder = "b".repeat(511);
        ExecutionStreamCheckpointCoordinator.PublishedDelta sized =
                TenantContext.call(
                        TENANT_ID,
                        () ->
                                checkpoints.append(
                                        run, 1, ModelOutputDelta.reasoning(remainder), STARTED_AT));
        assertThat(sized.outputDeltaSequence()).isEqualTo(2);
        assertThat(sized.checkpointed()).isTrue();
        assertStream(run.executionId(), 1, 1, 2, "a" + remainder, "");
        long staleVersion = currentStream(run.executionId()).getVersion();

        // reasoning 切到 answer 的 phase 边界先到，短增量也立即收敛。
        Instant phaseAt = STARTED_AT.plusMillis(1);
        ExecutionStreamCheckpointCoordinator.PublishedDelta phased =
                TenantContext.call(
                        TENANT_ID,
                        () -> checkpoints.append(run, 1, ModelOutputDelta.answer("z"), phaseAt));
        assertThat(phased.checkpointed()).isTrue();
        assertStream(run.executionId(), 1, 1, 3, "a" + remainder, "z");

        // 距离上次 checkpoint 250ms 后，同 phase 的一个字符也立即收敛。
        Instant timedAt = phaseAt.plusMillis(250);
        ExecutionStreamCheckpointCoordinator.PublishedDelta timed =
                TenantContext.call(
                        TENANT_ID,
                        () -> checkpoints.append(run, 1, ModelOutputDelta.answer("q"), timedAt));
        assertThat(timed.checkpointed()).isTrue();
        assertStream(run.executionId(), 1, 1, 4, "a" + remainder, "zq");
        assertThat(
                        TenantContext.call(
                                TENANT_ID,
                                () ->
                                        streamCheckpoints.checkpoint(
                                                run.executionId(),
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
                                        streamCheckpoints.checkpoint(
                                                run.executionId(),
                                                currentStream(run.executionId()).getVersion(),
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

        // Redis 停服不影响 owner 继续执行；checkpoint 仍以数据库为权威持久化。
        REDIS.stop();

        // 切换 turn 会清空当前投影和序号；旧 turn 随后的强制写入必须被 fencing 拒绝。
        TenantContext.run(
                TENANT_ID, () -> checkpoints.beginModelInvocation(run, 2, timedAt.plusMillis(1)));
        TenantContext.run(
                TENANT_ID,
                () ->
                        checkpoints.append(
                                run, 2, ModelOutputDelta.answer("new"), timedAt.plusMillis(2)));
        TenantContext.run(
                TENANT_ID,
                () ->
                        checkpoints.flush(
                                run,
                                2,
                                ExecutionStreamCheckpointCoordinator.Boundary.MODEL_INVOCATION,
                                timedAt.plusMillis(3)));
        assertStream(run.executionId(), 1, 2, 1, "", "new");
        TenantContext.run(
                TENANT_ID,
                () ->
                        checkpoints.append(
                                run, 2, ModelOutputDelta.answer("!"), timedAt.plusMillis(4)));
        TenantContext.run(
                TENANT_ID,
                () ->
                        checkpoints.flush(
                                run,
                                2,
                                ExecutionStreamCheckpointCoordinator.Boundary.LEASE_RELEASE,
                                timedAt.plusMillis(5)));
        assertStream(run.executionId(), 1, 2, 2, "", "new!");
        assertThatThrownBy(
                        () ->
                                TenantContext.run(
                                        TENANT_ID,
                                        () ->
                                                checkpoints.flush(
                                                        run,
                                                        1,
                                                        ExecutionStreamCheckpointCoordinator
                                                                .Boundary.TERMINAL,
                                                        timedAt.plusMillis(6))))
                .isInstanceOf(ExecutionOwnershipLostException.class);
    }

    private AgentExecutionPort.ClaimedAgentExecution createRunningExecution(String suffix)
            throws Exception {
        Cookie login = login();
        String conversationSessionId = createSession(login, suffix);
        String response =
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
        String executionId =
                json.readTree(response).path("execution").path("executionId").asString();
        jdbc.update(
                """
                update agent_execution set status='running', phase='RECEIVED', execution_attempt_number=1,
                  owner_instance_id=?, started_at=?, heartbeat_at=?, lease_expires_at=?,
                  active_segment_started_at=?, hard_deadline_at=? where execution_id=?
                """,
                OWNER_ID,
                Timestamp.from(STARTED_AT),
                Timestamp.from(STARTED_AT),
                Timestamp.from(STARTED_AT.plusSeconds(60)),
                Timestamp.from(STARTED_AT),
                Timestamp.from(STARTED_AT.plusSeconds(600)),
                executionId);
        return new AgentExecutionPort.ClaimedAgentExecution(TENANT_ID, executionId, OWNER_ID, 1);
    }

    private void assertStream(
            String executionId,
            long attempt,
            long turn,
            long sequence,
            String reasoning,
            String answer) {
        AgentExecutionStreamCheckpoint stream = currentStream(executionId);
        assertThat(stream.getExecutionAttemptNumber()).isEqualTo(attempt);
        assertThat(stream.getModelInvocationSequence()).isEqualTo(turn);
        assertThat(stream.getOutputDeltaSequence()).isEqualTo(sequence);
        assertThat(stream.getReasoning()).isEqualTo(reasoning);
        assertThat(stream.getAnswer()).isEqualTo(answer);
    }

    private AgentExecutionStreamCheckpoint currentStream(String executionId) {
        return TenantContext.call(
                TENANT_ID, () -> streamCheckpoints.findByExecutionId(executionId));
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
}
