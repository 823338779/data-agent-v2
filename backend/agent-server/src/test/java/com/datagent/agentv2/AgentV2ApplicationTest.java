package com.datagent.agentv2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datagent.agentv2.conversation.react.ReactExecutionEngine;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
class AgentV2ApplicationTest {

    private static final String ORIGINAL_TITLE = "Original title";
    private static final String RENAMED_TITLE = "Renamed title";
    private static final String STALE_TITLE = "Stale title";
    private static final String BLANK_TITLE = "   ";
    private static final String TITLE_CHARACTER = "x";
    private static final int TOO_LONG_TITLE_LENGTH = 121;
    private static final Pattern SESSION_VERSION_PATTERN = Pattern.compile("\"version\":([0-9]+)");
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
    @MockitoBean private ReactExecutionEngine executionEngine;

    @Test
    void fixedUserCanCreateListAndReadConversation() throws Exception {

        String conversationSessionId =
                mvc.perform(
                                post("/api/v1/agent-sessions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"title\":\"First chat\",\"tag\":\"local\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.title").value("First chat"))
                        .andExpect(jsonPath("$.tag").value("local"))
                        .andExpect(jsonPath("$.sessionType").doesNotExist())
                        .andExpect(jsonPath("$.messages").isEmpty())
                        .andExpect(jsonPath("$.execution").doesNotExist())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
                        .replaceAll(".*\\\"conversationSessionId\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mvc.perform(get("/api/v1/agent-sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].conversationSessionId").value(conversationSessionId))
                .andExpect(jsonPath("$[0].status").value("idle"))
                .andExpect(jsonPath("$[0].messages").doesNotExist())
                .andExpect(jsonPath("$[0].execution").doesNotExist());
        mvc.perform(get("/api/v1/agent-sessions/{conversationSessionId}", conversationSessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationSessionId").value(conversationSessionId));
    }

    @Test
    void fixedUserCanRenameConversationWithOptimisticLocking() throws Exception {
        MvcResult created =
                mvc.perform(
                                post("/api/v1/agent-sessions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"title\":\"" + ORIGINAL_TITLE + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String createdBody = created.getResponse().getContentAsString();
        String conversationSessionId =
                createdBody.replaceAll(
                        ".*\\\"conversationSessionId\\\":\\\"([^\\\"]+)\\\".*", "$1");
        Matcher versionMatcher = SESSION_VERSION_PATTERN.matcher(createdBody);
        assertThat(versionMatcher.find()).isTrue();
        long version = Long.parseLong(versionMatcher.group(1));

        // 正确版本只更新标题，并返回递增后的会话版本。
        mvc.perform(
                        patch(
                                        "/api/v1/agent-sessions/{conversationSessionId}",
                                        conversationSessionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"title\":\"  "
                                                + RENAMED_TITLE
                                                + "  \",\"expectedVersion\":"
                                                + version
                                                + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value(RENAMED_TITLE))
                .andExpect(jsonPath("$.version").value(version + 1));

        // 旧版本不能覆盖已经保存的新标题。
        mvc.perform(
                        patch(
                                        "/api/v1/agent-sessions/{conversationSessionId}",
                                        conversationSessionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"title\":\""
                                                + STALE_TITLE
                                                + "\",\"expectedVersion\":"
                                                + version
                                                + "}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/agent-sessions/{conversationSessionId}", conversationSessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value(RENAMED_TITLE));
    }

    @Test
    void conversationRenameRejectsInvalidTitlesWithoutChangingStoredTitle() throws Exception {
        String createdBody =
                mvc.perform(
                                post("/api/v1/agent-sessions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"title\":\"" + ORIGINAL_TITLE + "\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String conversationSessionId =
                createdBody.replaceAll(
                        ".*\\\"conversationSessionId\\\":\\\"([^\\\"]+)\\\".*", "$1");
        Matcher versionMatcher = SESSION_VERSION_PATTERN.matcher(createdBody);
        assertThat(versionMatcher.find()).isTrue();
        long version = Long.parseLong(versionMatcher.group(1));

        // Bean Validation 在进入应用服务前拒绝空白和超长标题。
        mvc.perform(
                        patch(
                                        "/api/v1/agent-sessions/{conversationSessionId}",
                                        conversationSessionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"title\":\""
                                                + BLANK_TITLE
                                                + "\",\"expectedVersion\":"
                                                + version
                                                + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        patch(
                                        "/api/v1/agent-sessions/{conversationSessionId}",
                                        conversationSessionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"title\":\""
                                                + TITLE_CHARACTER.repeat(TOO_LONG_TITLE_LENGTH)
                                                + "\",\"expectedVersion\":"
                                                + version
                                                + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/agent-sessions/{conversationSessionId}", conversationSessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value(ORIGINAL_TITLE));
    }

    @Test
    void submittingMessagePersistsUserMessageAndStartsExecutionInSameRequest() throws Exception {
        String body =
                mvc.perform(
                                post("/api/v1/agent-sessions")
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
                                .header("Idempotency-Key", "message-test")
                                .header("X-Request-Id", "request_message_test")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"hello ollama\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("hello ollama"))
                .andExpect(jsonPath("$.execution.status").value("running"))
                .andExpect(jsonPath("$.execution.events[0].type").value("execution_started"))
                .andExpect(
                        jsonPath("$.execution.streamCheckpoint.phase").value("PREPARING_CONTEXT"));
    }

    @Test
    void eventStreamStartsWithOneAuthoritativeSessionSnapshotAndStaysOpen() throws Exception {
        String created =
                mvc.perform(
                                post("/api/v1/agent-sessions")
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
                                .header("Idempotency-Key", "stream-test")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"stream this\"}"))
                .andExpect(status().isAccepted());

        mvc.perform(
                        get(
                                        "/api/v1/agent-sessions/{conversationSessionId}/events",
                                        conversationSessionId)
                                .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/event-stream"))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.containsString(
                                                "event:session_snapshot")))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "event:execution_started"))))
                .andExpect(request().asyncStarted());

        // 即使携带旧游标，重连也必须先返回 PostgreSQL 权威快照，避免低延迟事件丢失。
        mvc.perform(
                        get(
                                        "/api/v1/agent-sessions/{conversationSessionId}/events",
                                        conversationSessionId)
                                .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.containsString(
                                                "event:session_snapshot")))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "event:execution_update"))))
                .andExpect(request().asyncStarted());
    }

    @Test
    void idleEventStreamDoesNotFollowAnExecutionStartedAfterItCloses() throws Exception {
        String created =
                mvc.perform(
                                post("/api/v1/agent-sessions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"title\":\"SSE live\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String conversationSessionId =
                created.replaceAll(".*\\\"conversationSessionId\\\":\\\"([^\\\"]+)\\\".*", "$1");

        // 未处理会话只返回一次权威快照并关闭，不订阅之后才启动的后台执行。
        MvcResult stream =
                mvc.perform(
                                get(
                                                "/api/v1/agent-sessions/{conversationSessionId}/events",
                                                conversationSessionId)
                                        .accept(MediaType.TEXT_EVENT_STREAM))
                        .andExpect(status().isOk())
                        .andExpect(request().asyncStarted())
                        .andReturn();
        mvc.perform(
                        post(
                                        "/api/v1/agent-sessions/{conversationSessionId}/messages",
                                        conversationSessionId)
                                .header("Idempotency-Key", "stream-live-test")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"stream live\"}"))
                .andExpect(status().isAccepted());

        assertThat(stream.getResponse().getContentAsString())
                .contains("event:session_snapshot")
                .doesNotContain("event:execution_update");
    }
}
