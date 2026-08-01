package com.datagent.agentv2;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
class AgentV2ApplicationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Autowired private MockMvc mvc;

    @Test
    void contextLoads() {}

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

        String id =
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
                        .andExpect(jsonPath("$.run").doesNotExist())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
                        .replaceAll(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mvc.perform(get("/api/v1/agent-sessions").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/v1/agent-sessions/{id}", id).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void submittingMessagePersistsUserMessageAndStartsRunInSameRequest() throws Exception {
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
        String id = body.replaceAll(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mvc.perform(
                        post("/api/v1/agent-sessions/{id}/messages", id)
                                .cookie(session)
                                .header("X-Request-Id", "request_message_test")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"hello ollama\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("hello ollama"))
                .andExpect(jsonPath("$.run.status").value("running"))
                .andExpect(jsonPath("$.run.events[0].type").value("run_started"))
                .andExpect(jsonPath("$.run.stream.phase").value("RECEIVED"));
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
