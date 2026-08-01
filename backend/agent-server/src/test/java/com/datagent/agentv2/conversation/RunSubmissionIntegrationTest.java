package com.datagent.agentv2.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
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

@SpringBootTest
@AutoConfigureMockMvc
class RunSubmissionIntegrationTest {

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

    @Test
    void messageSubmissionRequiresIdempotencyKey() throws Exception {
        Cookie login = login();
        String sessionId = createSession(login, "required-key");

        mvc.perform(
                        post("/api/v1/agent-sessions/{sessionId}/messages", sessionId)
                                .cookie(login)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"hello\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void serialReplayReturnsOriginalQueuedRunWithoutDuplicateMessage() throws Exception {
        Cookie login = login();
        String sessionId = createSession(login, "serial-replay");

        String first = submit(login, sessionId, "key-serial", "hello");
        String replay = submit(login, sessionId, "key-serial", "hello");

        assertThat(runId(replay)).isEqualTo(runId(first));
        assertCounts(sessionId, 1, 1);
        assertThat(json.readTree(first).path("run").path("status").asString()).isEqualTo("queued");
        assertThat(json.readTree(first).path("run").path("events").get(0).path("type").asString())
                .isEqualTo("run_queued");
        assertThat(
                        json.readTree(first)
                                .path("run")
                                .path("stream")
                                .path("executionAttempt")
                                .asLong())
                .isZero();
    }

    @Test
    void sameKeyWithDifferentPayloadReturnsStableConflict() throws Exception {
        Cookie login = login();
        String sessionId = createSession(login, "payload-conflict");
        submit(login, sessionId, "key-conflict", "first");

        mvc.perform(
                        post("/api/v1/agent-sessions/{sessionId}/messages", sessionId)
                                .cookie(login)
                                .header("Idempotency-Key", "key-conflict")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"second\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertCounts(sessionId, 1, 1);
    }

    @Test
    void concurrentReplayConvergesOnOneMessageAndRun() throws Exception {
        Cookie login = login();
        String sessionId = createSession(login, "concurrent-replay");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> submit(login, sessionId, "key-concurrent", "same"));
            var second = pool.submit(() -> submit(login, sessionId, "key-concurrent", "same"));

            assertThat(runId(first.get())).isEqualTo(runId(second.get()));
        }
        assertCounts(sessionId, 1, 1);
    }

    private String submit(Cookie login, String sessionId, String key, String content)
            throws Exception {
        return mvc.perform(
                        post("/api/v1/agent-sessions/{sessionId}/messages", sessionId)
                                .cookie(login)
                                .header("Idempotency-Key", key)
                                .header("X-Request-Id", "request-" + key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"" + content + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
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

    private String runId(String response) throws Exception {
        return json.readTree(response).path("run").path("id").asString();
    }

    private void assertCounts(String sessionId, int messages, int runs) {
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from agent_message where session_id = ?",
                                Integer.class,
                                sessionId))
                .isEqualTo(messages);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from agent_run where session_id = ?",
                                Integer.class,
                                sessionId))
                .isEqualTo(runs);
    }
}
