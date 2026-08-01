package com.datagent.agentv2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
class AgentV2ApplicationTest {

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
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.username", () -> "default");
        registry.add("spring.data.redis.password", () -> "redis_test");
    }

    @AfterAll
    static void stopDatabase() {
        REDIS.stop();
        POSTGRES.stop();
    }

    @Autowired private MockMvc mvc;

    @Test
    void authenticationFailureUsesCorrelatedProblemDetail() throws Exception {
        mvc.perform(get("/api/v1/auth/session").header("X-Request-Id", "request_auth_test"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-Id", "request_auth_test"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.requestId").value("request_auth_test"));
    }

    @Test
    void authenticatedUserCanCreateListAndReadConversation() throws Exception {
        Cookie session = login();

        String conversationSessionId =
                mvc.perform(
                                post("/api/v1/agent-sessions")
                                        .cookie(session)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"title\":\"First chat\",\"tag\":\"local\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.title").value("First chat"))
                        .andExpect(jsonPath("$.tag").value("local"))
                        .andExpect(jsonPath("$.sessionType").value("INTERACTIVE"))
                        .andExpect(jsonPath("$.messages").isEmpty())
                        .andExpect(jsonPath("$.execution").doesNotExist())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
                        .replaceAll(".*\\\"conversationSessionId\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mvc.perform(get("/api/v1/agent-sessions").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].conversationSessionId").value(conversationSessionId));
        mvc.perform(
                        get("/api/v1/agent-sessions/{conversationSessionId}", conversationSessionId)
                                .cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationSessionId").value(conversationSessionId));
    }

    @Test
    void submittingMessagePersistsUserMessageAndStartsExecutionInSameRequest() throws Exception {
        Cookie session = login();
        String body =
                mvc.perform(
                                post("/api/v1/agent-sessions")
                                        .cookie(session)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"title\":\"Streaming chat\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String conversationSessionId =
                body.replaceAll(".*\\\"conversationSessionId\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mvc.perform(
                        post(
                                        "/api/v1/agent-sessions/{conversationSessionId}/messages",
                                        conversationSessionId)
                                .cookie(session)
                                .header("Idempotency-Key", "message-test")
                                .header("X-Request-Id", "request_message_test")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"hello ollama\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("hello ollama"))
                .andExpect(jsonPath("$.execution.status").value("queued"))
                .andExpect(jsonPath("$.execution.events[0].type").value("execution_queued"))
                .andExpect(jsonPath("$.execution.streamCheckpoint.phase").value("QUEUED"));
    }

    @Test
    void eventStreamReplaysPersistedExecutionEventsAndStaysOpen() throws Exception {
        Cookie session = login();
        String created =
                mvc.perform(
                                post("/api/v1/agent-sessions")
                                        .cookie(session)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"title\":\"SSE replay\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String conversationSessionId =
                created.replaceAll(".*\\\"conversationSessionId\\\":\\\"([^\\\"]+)\\\".*", "$1");
        mvc.perform(
                        post(
                                        "/api/v1/agent-sessions/{conversationSessionId}/messages",
                                        conversationSessionId)
                                .cookie(session)
                                .header("Idempotency-Key", "stream-test")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"stream this\"}"))
                .andExpect(status().isAccepted());

        mvc.perform(
                        get(
                                        "/api/v1/agent-sessions/{conversationSessionId}/events",
                                        conversationSessionId)
                                .cookie(session)
                                .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/event-stream"))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.containsString(
                                                "event:execution_queued")))
                .andExpect(request().asyncStarted());
    }

    @Test
    void eventStreamPublishesEventsPersistedAfterConnectionOpens() throws Exception {
        Cookie session = login();
        String created =
                mvc.perform(
                                post("/api/v1/agent-sessions")
                                        .cookie(session)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"title\":\"SSE live\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String conversationSessionId =
                created.replaceAll(".*\\\"conversationSessionId\\\":\\\"([^\\\"]+)\\\".*", "$1");

        // Open the stream before the event exists to verify live delivery rather than replay.
        MvcResult stream =
                mvc.perform(
                                get(
                                                "/api/v1/agent-sessions/{conversationSessionId}/events",
                                                conversationSessionId)
                                        .cookie(session)
                                        .accept(MediaType.TEXT_EVENT_STREAM))
                        .andExpect(status().isOk())
                        .andExpect(request().asyncStarted())
                        .andReturn();
        mvc.perform(
                        post(
                                        "/api/v1/agent-sessions/{conversationSessionId}/messages",
                                        conversationSessionId)
                                .cookie(session)
                                .header("Idempotency-Key", "stream-live-test")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"stream live\"}"))
                .andExpect(status().isAccepted());

        assertThat(stream.getResponse().getContentAsString()).contains("event:execution_queued");
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
        String token = setCookie.substring("DATA_AGENT_SESSION=".length(), setCookie.indexOf(';'));
        return new Cookie("DATA_AGENT_SESSION", token);
    }
}
