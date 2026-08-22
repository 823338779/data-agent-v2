package com.datagent.agentv2.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class ActivitySseIntegrationTest {

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

    @Test
    void connectionImmediatelyReceivesCurrentActivitySnapshotAndCleansUp() throws Exception {
        Cookie login = login();
        createSession(login);

        MvcResult stream =
                mvc.perform(get("/api/v1/agent-sessions/activity-events").cookie(login))
                        .andExpect(status().isOk())
                        .andExpect(request().asyncStarted())
                        .andReturn();

        // 初始快照必须在轮询周期之前可见，且携带当前用户的会话活动字段。
        Awaitility.await()
                .atMost(Duration.ofSeconds(3))
                .untilAsserted(
                        () -> {
                            String content = stream.getResponse().getContentAsString();
                            assertThat(content).contains("event:activity_snapshot");
                            assertThat(content).contains("conversationSessionId");
                            assertThat(content).contains("orderingVersion");
                        });
        stream.getRequest().getAsyncContext().complete();
    }

    private void createSession(Cookie login) throws Exception {
        mvc.perform(
                        post("/api/v1/agent-sessions")
                                .cookie(login)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"activity-sse\"}"))
                .andExpect(status().isCreated());
    }

    private Cookie login() {
        return new Cookie("ignored", "ignored");
    }
}
