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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
class ConversationSummarySseIntegrationTest {

    private static final String SUMMARY_EVENTS_PATH = "/api/v1/agent-sessions/summary-events";
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

    @Test
    void connectionImmediatelyReceivesCurrentConversationSummarySnapshotAndCleansUp()
            throws Exception {
        Cookie login = login();
        createSession(login);

        MvcResult stream =
                mvc.perform(get(SUMMARY_EVENTS_PATH).cookie(login))
                        .andExpect(status().isOk())
                        .andExpect(request().asyncStarted())
                        .andReturn();

        // 初始快照必须在轮询周期之前可见，且携带当前用户的会话摘要字段。
        Awaitility.await()
                .atMost(Duration.ofSeconds(3))
                .untilAsserted(
                        () -> {
                            String content = stream.getResponse().getContentAsString();
                            assertThat(content).contains("event:conversation_summary_snapshot");
                            assertThat(content).contains("conversationSessionId");
                            assertThat(content).doesNotContain("orderingVersion");
                        });
        stream.getRequest().getAsyncContext().complete();
    }

    private void createSession(Cookie login) throws Exception {
        mvc.perform(
                        post("/api/v1/agent-sessions")
                                .cookie(login)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"conversation-summary-sse\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void existingConnectionReceivesConversationsCreatedAfterItsInitialSnapshot() throws Exception {
        MvcResult stream =
                mvc.perform(get(SUMMARY_EVENTS_PATH))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        try {
            Awaitility.await()
                    .atMost(Duration.ofSeconds(3))
                    .untilAsserted(
                            () ->
                                    assertThat(stream.getResponse().getContentAsString())
                                            .contains("event:conversation_summary_snapshot"));
            int before = stream.getResponse().getContentAsString().length();
            createSession(login());
            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(
                            () ->
                                    assertThat(
                                                    stream.getResponse()
                                                            .getContentAsString()
                                                            .substring(before))
                                            .contains("event:conversation_summary_snapshot"));
        } finally {
            stream.getRequest().getAsyncContext().complete();
        }
    }

    private Cookie login() {
        return new Cookie("ignored", "ignored");
    }
}
