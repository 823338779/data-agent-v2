package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.hierarchical.strategy.HierarchicalConversationContextStrategy;
import com.datagent.agentv2.platform.context.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = "data-agent.context.strategy=hierarchical")
class HierarchicalFullHistoryIntegrationTest {
    private static final String TENANT_ID = "tenant_local";
    private static final String SESSION_ID = "session_history";
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
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Autowired private HierarchicalConversationContextStrategy strategy;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private ContextSummaryModelPort summaryModel;

    @BeforeEach
    void createHistoryWithEmptyAssistantRows() {
        jdbc.update("delete from agent_execution_event");
        jdbc.update("delete from agent_execution_stream_checkpoint");
        jdbc.update("delete from conversation_context_hierarchical_checkpoint");
        jdbc.update("delete from conversation_context_hierarchical_cold");
        jdbc.update("update conversation_message set execution_id = null");
        jdbc.update("delete from agent_execution");
        jdbc.update("delete from conversation_message");
        jdbc.update("delete from conversation_session");

        jdbc.update(
                "insert into tenant (tenant_id, name) values (?, ?) on conflict (tenant_id) do nothing",
                TENANT_ID,
                "History");
        jdbc.update(
                """
                insert into conversation_session (
                    conversation_session_id, tenant_id, title, conversation_message_sequence
                ) values (?, ?, ?, 6)
                """,
                SESSION_ID,
                TENANT_ID,
                "History");
        insertExecution(
                "execution_cancelled",
                "message_user_1",
                "message_assistant_1",
                1,
                2,
                "cancelled question",
                "cancelled");
        insertExecution(
                "execution_failed",
                "message_user_2",
                "message_assistant_2",
                3,
                4,
                "failed question",
                "failed");
        insertExecution(
                "execution_running",
                "message_user_3",
                "message_assistant_3",
                5,
                6,
                "latest question",
                "running");
    }

    @Test
    void includesEveryUserTurnRegardlessOfExecutionTerminalState() {
        when(summaryModel.identity()).thenReturn(new ContextSummaryModelIdentity("test", "test"));
        ConversationContextRequest request =
                new ConversationContextRequest(
                        new ContextExecutionRef(TENANT_ID, SESSION_ID, "execution_running", 6L),
                        new ContextBudget(100_000, 1_000, 1_000, 0),
                        List.of(),
                        List.of());

        PreparedConversationContext prepared =
                TenantContext.call(TENANT_ID, () -> strategy.prepare(request));

        assertThat(prepared.messages())
                .filteredOn(message -> message.role() == ContextMessage.Role.USER)
                .extracting(ContextMessage::content)
                .containsExactly("cancelled question", "failed question", "latest question");
        assertThat(prepared.messages().getLast().role()).isEqualTo(ContextMessage.Role.USER);
        assertThat(prepared.messages().getLast().content()).isEqualTo("latest question");
        assertThat(prepared.messages())
                .noneMatch(
                        message ->
                                message.role() == ContextMessage.Role.ASSISTANT
                                        && message.content().isEmpty());
        verify(summaryModel, never()).summarize(any());
    }

    private void insertExecution(
            String executionId,
            String userMessageId,
            String assistantMessageId,
            long userSequence,
            long assistantSequence,
            String userContent,
            String status) {
        jdbc.update(
                """
                insert into conversation_message (
                    conversation_message_id, tenant_id, conversation_session_id,
                    role, content, conversation_message_sequence
                ) values (?, ?, ?, 'user', ?, ?)
                """,
                userMessageId,
                TENANT_ID,
                SESSION_ID,
                userContent,
                userSequence);
        jdbc.update(
                """
                insert into agent_execution (
                    execution_id, tenant_id, conversation_session_id,
                    triggering_conversation_message_id, status,
                    request_idempotency_key, request_payload_hash, hard_deadline_at
                ) values (?, ?, ?, ?, ?, ?, ?, current_timestamp + interval '1 day')
                """,
                executionId,
                TENANT_ID,
                SESSION_ID,
                userMessageId,
                status,
                "key-" + executionId,
                "0".repeat(64));
        jdbc.update(
                "update conversation_message set execution_id = ? where conversation_message_id = ?",
                executionId,
                userMessageId);
        jdbc.update(
                """
                insert into conversation_message (
                    conversation_message_id, tenant_id, conversation_session_id, execution_id,
                    role, content, conversation_message_sequence
                ) values (?, ?, ?, ?, 'assistant', '', ?)
                """,
                assistantMessageId,
                TENANT_ID,
                SESSION_ID,
                executionId,
                assistantSequence);
    }
}
