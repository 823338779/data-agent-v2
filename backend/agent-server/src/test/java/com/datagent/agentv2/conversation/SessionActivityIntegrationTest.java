package com.datagent.agentv2.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    void sessionsAreOrderedByUpdatedAtDescending() throws Exception {
        Cookie login = login();
        String first = createSession(login, "first");
        String second = createSession(login, "second");

        // 列表顺序只由会话更新时间决定，不再暴露或保存用户手工排序状态。
        setUpdatedAt(first, "2026-08-22T10:00:00Z");
        setUpdatedAt(second, "2026-08-22T09:00:00Z");
        JsonNode sessions = listSessions(login);
        assertThat(sessions.get(0).path("conversationSessionId").asString()).isEqualTo(first);
        assertThat(sessions.get(1).path("conversationSessionId").asString()).isEqualTo(second);
        assertThat(sessions.get(0).has("sortOrder")).isFalse();
        assertThat(sessions.get(0).has("orderingVersion")).isFalse();
        assertThat(sessions.get(0).has("unread")).isFalse();
        assertThat(sessions.get(0).has("lastNotifiableEventSequence")).isFalse();

        setUpdatedAt(second, "2026-08-22T11:00:00Z");
        assertThat(listSessions(login).get(0).path("conversationSessionId").asString())
                .isEqualTo(second);
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

    private void setUpdatedAt(String conversationSessionId, String updatedAt) {
        jdbc.update(
                "update conversation_session set updated_at = cast(? as timestamptz) where conversation_session_id = ?",
                updatedAt,
                conversationSessionId);
    }
}
