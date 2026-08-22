package com.datagent.agentv2.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

@SpringBootTest
@AutoConfigureMockMvc
class SessionActivityIntegrationTest {

    private static final String TENANT_ID = "tenant_local";
    private static final String USER_ID = "user_admin";
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

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    @Test
    void newlyCreatedSessionsArePinnedWithoutActivityReordering() throws Exception {
        Cookie login = login();
        String first = createSession(login, "first");
        String second = createSession(login, "second");

        JsonNode sessions = listSessions(login);
        assertThat(sessions.get(0).path("conversationSessionId").asString()).isEqualTo(second);
        assertThat(sessions.get(1).path("conversationSessionId").asString()).isEqualTo(first);
        assertThat(sessions.get(0).path("sortOrder").asLong()).isZero();
        assertThat(sessions.get(0).path("orderingVersion").asLong()).isPositive();
        assertThat(sessions.get(0).path("unread").asBoolean()).isFalse();

        // 共享活动时间变化不允许隐式覆盖用户手工顺序。
        jdbc.update(
                "update conversation_session set last_activity_at = current_timestamp + interval '1 hour' where conversation_session_id = ?",
                first);
        JsonNode afterActivity = listSessions(login);
        assertThat(afterActivity.get(0).path("conversationSessionId").asString()).isEqualTo(second);
    }

    @Test
    void markReadIsMonotonicAndReorderRejectsAStaleVersion() throws Exception {
        Cookie login = login();
        String first = createSession(login, "ordering-first");
        String second = createSession(login, "ordering-second");

        // 读游标最多推进到当前可通知水位，并保留随后到达的新通知。
        setLastNotifiable(first, 5L);
        markRead(login, first, 99L).andExpect(jsonPath("$.unread").value(false));
        assertThat(lastRead(first)).isEqualTo(5L);
        setLastNotifiable(first, 6L);
        markRead(login, first, 5L)
                .andExpect(jsonPath("$.unread").value(true))
                .andExpect(jsonPath("$.lastNotifiableEventSequence").value(6));
        assertThat(lastRead(first)).isEqualTo(5L);

        // 完整重排递增版本；旧设备再次提交原版本时得到独立冲突码。
        long expectedVersion = listSessions(login).get(0).path("orderingVersion").asLong();
        String body = reorderBody(first, second, expectedVersion);
        mvc.perform(
                        post("/api/v1/agent-sessions:reorder")
                                .cookie(login)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderingVersion").value(expectedVersion + 1));
        mvc.perform(
                        post("/api/v1/agent-sessions:reorder")
                                .cookie(login)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_ORDER_CONFLICT"));
    }

    private org.springframework.test.web.servlet.ResultActions markRead(
            Cookie login, String conversationSessionId, long upToSequence) throws Exception {
        return mvc.perform(
                        post(
                                        "/api/v1/agent-sessions/{conversationSessionId}:mark-read",
                                        conversationSessionId)
                                .cookie(login)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"upToSequence\":" + upToSequence + "}"))
                .andExpect(status().isOk());
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

    private JsonNode listSessions(Cookie login) throws Exception {
        String body =
                mvc.perform(get("/api/v1/agent-sessions").cookie(login))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.readTree(body);
    }

    private Cookie login() {
        return new Cookie("ignored", "ignored");
    }

    private void setLastNotifiable(String conversationSessionId, long sequence) {
        jdbc.update(
                "update conversation_session set last_notifiable_event_sequence = ? where tenant_id = ? and conversation_session_id = ?",
                sequence,
                TENANT_ID,
                conversationSessionId);
    }

    private long lastRead(String conversationSessionId) {
        return jdbc.queryForObject(
                "select last_read_event_sequence from conversation_session_user_state where tenant_id = ? and user_id = ? and conversation_session_id = ?",
                Long.class,
                TENANT_ID,
                USER_ID,
                conversationSessionId);
    }

    private static String reorderBody(String first, String second, long expectedVersion) {
        return "{\"conversationSessionIds\":[\""
                + first
                + "\",\""
                + second
                + "\"],\"expectedOrderingVersion\":"
                + expectedVersion
                + "}";
    }
}
