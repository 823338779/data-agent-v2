package com.datagent.agentv2.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datagent.agentv2.conversation.react.ReactExecutionEngine;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class AgentExecutionSubmissionIntegrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stopContainers() {
        POSTGRES.stop();
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @MockitoBean private ReactExecutionEngine executionEngine;

    @Test
    void messageSubmissionRequiresIdempotencyKey() throws Exception {
        Cookie login = login();
        String conversationSessionId = createSession(login, "required-key");

        mvc.perform(
                        post(
                                        "/api/v1/agent-sessions/{conversationSessionId}/messages",
                                        conversationSessionId)
                                .cookie(login)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"hello\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void messageSubmissionReturnsResolvedRequestIdWhenHeaderIsAbsent() throws Exception {
        Cookie login = login();
        String conversationSessionId = createSession(login, "generated-request-id");

        String requestId =
                mvc.perform(
                                post(
                                                "/api/v1/agent-sessions/{conversationSessionId}/messages",
                                                conversationSessionId)
                                        .cookie(login)
                                        .header("Idempotency-Key", "key-generated-request-id")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"content\":\"hello\"}"))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getHeader("X-Request-Id");

        assertThat(requestId).isNotBlank().isNotEqualTo("request_unknown");
    }

    @Test
    void serialReplayReturnsOriginalRunningExecutionWithoutDuplicateMessage() throws Exception {
        Cookie login = login();
        String conversationSessionId = createSession(login, "serial-replay");

        String first = submit(login, conversationSessionId, "key-serial", "hello");
        String replay = submit(login, conversationSessionId, "key-serial", "hello");

        assertThat(executionId(replay)).isEqualTo(executionId(first));
        assertCounts(conversationSessionId, 2, 1);
        assertContentCenteredMessages(conversationSessionId, "hello");
        assertThat(json.readTree(first).path("execution").path("status").asString())
                .isEqualTo("running");
        assertThat(
                        json.readTree(first)
                                .path("execution")
                                .path("events")
                                .get(0)
                                .path("type")
                                .asString())
                .isEqualTo("execution_started");
        assertThat(
                        json.readTree(first)
                                .path("execution")
                                .path("streamCheckpoint")
                                .path("phase")
                                .asString())
                .isEqualTo("PREPARING_CONTEXT");
    }

    @Test
    void sameKeyWithDifferentPayloadReturnsStableConflict() throws Exception {
        Cookie login = login();
        String conversationSessionId = createSession(login, "payload-conflict");
        submit(login, conversationSessionId, "key-conflict", "first");

        mvc.perform(
                        post(
                                        "/api/v1/agent-sessions/{conversationSessionId}/messages",
                                        conversationSessionId)
                                .cookie(login)
                                .header("Idempotency-Key", "key-conflict")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"second\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertCounts(conversationSessionId, 2, 1);
    }

    @Test
    void activeExecutionConflictsWithAnotherSubmission() throws Exception {
        Cookie login = login();
        String conversationSessionId = createSession(login, "default-active-conflict");
        submit(login, conversationSessionId, "key-active-first", "first");

        mvc.perform(
                        post(
                                        "/api/v1/agent-sessions/{conversationSessionId}/messages",
                                        conversationSessionId)
                                .cookie(login)
                                .header("Idempotency-Key", "key-active-second")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"second\"}"))
                .andExpect(status().isConflict());

        assertCounts(conversationSessionId, 2, 1);
    }

    @Test
    void concurrentReplayConvergesOnOneMessageAndExecution() throws Exception {
        Cookie login = login();
        String conversationSessionId = createSession(login, "concurrent-replay");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first =
                    pool.submit(
                            () -> submit(login, conversationSessionId, "key-concurrent", "same"));
            var second =
                    pool.submit(
                            () -> submit(login, conversationSessionId, "key-concurrent", "same"));

            assertThat(executionId(first.get())).isEqualTo(executionId(second.get()));
        }
        assertCounts(conversationSessionId, 2, 1);
    }

    private String submit(Cookie login, String conversationSessionId, String key, String content)
            throws Exception {
        return mvc.perform(
                        post(
                                        "/api/v1/agent-sessions/{conversationSessionId}/messages",
                                        conversationSessionId)
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
        return json.readTree(body).path("conversationSessionId").asString();
    }

    private Cookie login() {
        return new Cookie("ignored", "ignored");
    }

    private String executionId(String response) throws Exception {
        return json.readTree(response).path("execution").path("executionId").asString();
    }

    private void assertCounts(String conversationSessionId, int messages, int executions) {
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from conversation_message where conversation_session_id = ?",
                                Integer.class,
                                conversationSessionId))
                .isEqualTo(messages);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from agent_execution where conversation_session_id = ?",
                                Integer.class,
                                conversationSessionId))
                .isEqualTo(executions);
    }

    private void assertContentCenteredMessages(String conversationSessionId, String userContent) {
        List<MessageRow> rows =
                jdbc.query(
                        """
                        select role, content, thinking_process::text
                          from conversation_message
                         where conversation_session_id = ?
                         order by conversation_message_sequence
                        """,
                        (resultSet, rowNumber) ->
                                new MessageRow(
                                        resultSet.getString("role"),
                                        resultSet.getString("content"),
                                        resultSet.getString("thinking_process")),
                        conversationSessionId);
        assertThat(rows)
                .containsExactly(
                        new MessageRow("user", userContent, "[]"),
                        new MessageRow("assistant", "", "[]"));
    }

    private record MessageRow(String role, String content, String thinkingProcess) {}
}
