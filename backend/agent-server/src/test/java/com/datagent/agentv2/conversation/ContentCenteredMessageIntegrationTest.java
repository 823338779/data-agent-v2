package com.datagent.agentv2.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.MessageRole;
import com.datagent.agentv2.conversation.react.ReactExecutionEngine;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.platform.context.TenantContext;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ContentCenteredMessageIntegrationTest {

    private static final String TENANT_ID = "tenant_local";
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
    @Autowired private ConversationMessageService messages;
    @Autowired private TransactionTemplate transactions;
    @MockitoBean private ReactExecutionEngine executionEngine;

    @BeforeEach
    void clearConversationData() {
        jdbc.update("delete from agent_execution_event");
        jdbc.update("delete from agent_execution_stream_checkpoint");
        jdbc.update("update conversation_message set execution_id = null");
        jdbc.update("delete from agent_execution");
        jdbc.update("delete from conversation_message");
        jdbc.update("delete from conversation_session");
    }

    @Test
    void submissionCreatesOneAssistantAndMessageQueriesUseTheCenterRows() throws Exception {
        Cookie login = login();
        String conversationSessionId = createSession(login);
        String executionId = submit(login, conversationSessionId);

        assertThat(roleCount(conversationSessionId, "user")).isEqualTo(1);
        assertThat(roleCount(conversationSessionId, "assistant")).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                """
                                select count(*) from conversation_message
                                 where execution_id = ? and role = 'assistant'
                                   and content = '' and thinking_process = '[]'::jsonb
                                """,
                                Integer.class,
                                executionId))
                .isEqualTo(1);

        // 锁查询和有界会话查询必须返回同一组真实中心消息，不生成额外协议辅助行。
        ConversationMessage locked =
                TenantContext.call(
                        TENANT_ID,
                        () ->
                                transactions.execute(
                                        ignored ->
                                                messages.lockAssistantByExecutionId(executionId)));
        assertThat(locked).isNotNull();
        assertThat(locked.getExecutionId()).isEqualTo(executionId);
        assertThat(locked.getThinkingProcess()).isEmpty();

        List<ConversationMessage> executionMessages =
                TenantContext.call(TENANT_ID, () -> messages.findByExecutionId(executionId));
        assertThat(executionMessages)
                .extracting(ConversationMessage::getRole)
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT);
        List<ConversationMessage> fullSessionMessages =
                TenantContext.call(
                        TENANT_ID,
                        () -> messages.findByConversationSessionId(conversationSessionId));
        assertThat(fullSessionMessages)
                .extracting(ConversationMessage::getRole)
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT);
        List<ConversationMessage> boundedSessionMessages =
                TenantContext.call(
                        TENANT_ID,
                        () -> messages.findByConversationSessionIdUpTo(conversationSessionId, 1L));
        assertThat(boundedSessionMessages)
                .extracting(ConversationMessage::getRole)
                .containsExactly(MessageRole.USER);
    }

    private int roleCount(String conversationSessionId, String role) {
        return jdbc.queryForObject(
                "select count(*) from conversation_message where conversation_session_id = ? and"
                        + " role = ?",
                Integer.class,
                conversationSessionId,
                role);
    }

    private String submit(Cookie login, String conversationSessionId) throws Exception {
        String body =
                mvc.perform(
                                post(
                                                "/api/v1/agent-sessions/{conversationSessionId}/messages",
                                                conversationSessionId)
                                        .cookie(login)
                                        .header("Idempotency-Key", "submit-key")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"content\":\"hello\"}"))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.readTree(body).path("execution").path("executionId").asString();
    }

    private String createSession(Cookie login) throws Exception {
        String body =
                mvc.perform(
                                post("/api/v1/agent-sessions")
                                        .cookie(login)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"title\":\"content-centered\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.readTree(body).path("conversationSessionId").asString();
    }

    private Cookie login() {
        return new Cookie("ignored", "ignored");
    }
}
