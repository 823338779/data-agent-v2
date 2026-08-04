package com.datagent.agentv2.conversation.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.ModelThinkingProcessItem;
import com.datagent.agentv2.conversation.domain.ThinkingProcessStatus;
import com.datagent.agentv2.conversation.domain.ToolThinkingProcessItem;
import com.datagent.agentv2.conversation.exception.ExecutionOwnershipLostException;
import com.datagent.agentv2.conversation.react.ModelToolCall;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.conversation.tool.ToolExecutionResult;
import com.datagent.agentv2.persistence.entity.ToolExecutionEntity;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ToolExecutionService;
import com.datagent.agentv2.platform.context.TenantContext;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
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

@SpringBootTest(properties = "data-agent.runtime.scheduling-enabled=false")
@AutoConfigureMockMvc
class ConversationProcessCoordinatorIntegrationTest {

    private static final String TENANT_ID = "tenant_local";
    private static final String OWNER_ID = "instance_process";
    private static final String STALE_OWNER_ID = "instance_stale";
    private static final String SESSION_COOKIE = "DATA_AGENT_SESSION";
    private static final String REDIS_PASSWORD = "redis_test";
    private static final String MODEL_PROVIDER = "codex";
    private static final String MODEL_NAME = "gpt-5";
    private static final String TOOL_NAME = "query_metric";
    private static final String TOOL_EXECUTION_ID = "tool_execution_1";
    private static final String TOOL_CALL_ID = "tool_call_1";
    private static final String MODEL_INVOCATION_ID = "model_invocation_tool_uuid";
    private static final String TOOL_EXECUTION_STATUS_PREPARED = "prepared";
    private static final String EMPTY_JSON = "{}";
    private static final String FIRST_THINKING_DELTA = "分析";
    private static final String SECOND_THINKING_DELTA = "完成";
    private static final String SECRET_VALUE = "do-not-store";
    private static final String REDACTED_VALUE = "[REDACTED]";
    private static final String FAILURE_CODE = "EXECUTION_FAILED";
    private static final String FAILURE_MESSAGE = "execution stopped";
    private static final Instant STARTED_AT = Instant.parse("2026-08-04T01:00:00Z");
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
    @Autowired private ConversationMessageService messages;
    @Autowired private ToolExecutionService toolExecutions;
    @Autowired private ConversationProcessCoordinator coordinator;

    @BeforeEach
    void clearConversationData() {
        jdbc.update("delete from notification_outbox_message");
        jdbc.update("delete from agent_execution_event");
        jdbc.update("delete from agent_execution_stream_checkpoint");
        jdbc.update("delete from agent_execution_attempt");
        jdbc.update("delete from tool_execution");
        jdbc.update("delete from model_invocation");
        jdbc.update("update conversation_message set execution_id = null");
        jdbc.update("delete from agent_execution");
        jdbc.update("delete from conversation_message");
        jdbc.update("delete from conversation_session");
    }

    @Test
    void processTransitionsKeepUuidSequenceAndDurationOnOneMessage() throws Exception {
        AgentExecutionPort.ClaimedAgentExecution run = createRunningExecution("thinking");

        UUID itemId =
                TenantContext.call(
                        TENANT_ID,
                        () ->
                                coordinator.startThinking(
                                        run,
                                        1L,
                                        MODEL_PROVIDER,
                                        MODEL_NAME,
                                        FIRST_THINKING_DELTA,
                                        STARTED_AT));
        TenantContext.run(
                TENANT_ID,
                () ->
                        coordinator.appendThinking(
                                run, itemId, SECOND_THINKING_DELTA, STARTED_AT.plusMillis(4)));
        TenantContext.run(
                TENANT_ID,
                () ->
                        coordinator.finishThinking(
                                run,
                                itemId,
                                ThinkingProcessStatus.SUCCEEDED,
                                STARTED_AT.plusMillis(9)));

        ConversationMessage assistant = assistant(run.executionId());
        assertThat(assistant.getThinkingProcess())
                .singleElement()
                .satisfies(
                        item -> {
                            ModelThinkingProcessItem thinking = (ModelThinkingProcessItem) item;
                            assertThat(thinking.processItemId()).isEqualTo(itemId);
                            assertThat(thinking.sequence()).isEqualTo(1L);
                            assertThat(thinking.content())
                                    .isEqualTo(FIRST_THINKING_DELTA + SECOND_THINKING_DELTA);
                            assertThat(thinking.durationMillis()).isEqualTo(9L);
                            assertThat(thinking.status())
                                    .isEqualTo(ThinkingProcessStatus.SUCCEEDED);
                        });
        assertThat(messageCount(run.executionId())).isEqualTo(1);
    }

    @Test
    void toolTransitionsKeepCompleteIdentityAndSanitizedPayloads() throws Exception {
        AgentExecutionPort.ClaimedAgentExecution run = createRunningExecution("tool");
        JsonNode arguments =
                json.readTree("{\"token\":\"" + SECRET_VALUE + "\",\"query\":\"select 1\"}");
        ModelToolCall call = new ModelToolCall(TOOL_CALL_ID, TOOL_NAME, arguments);

        UUID itemId =
                TenantContext.call(
                        TENANT_ID,
                        () ->
                                coordinator.prepareTool(
                                        run, 1L, call, TOOL_EXECUTION_ID, STARTED_AT));
        TenantContext.run(
                TENANT_ID, () -> coordinator.startTool(run, itemId, STARTED_AT.plusMillis(2)));
        TenantContext.run(
                TENANT_ID,
                () ->
                        coordinator.finishTool(
                                run,
                                itemId,
                                ToolExecutionResult.succeeded(json.readTree("{\"result\":\"ok\"}")),
                                STARTED_AT.plusMillis(7)));

        ToolThinkingProcessItem tool =
                (ToolThinkingProcessItem) assistant(run.executionId()).getThinkingProcess().get(0);
        assertThat(tool.processItemId()).isEqualTo(itemId);
        assertThat(tool.toolId()).isEqualTo(TOOL_NAME);
        assertThat(tool.toolExecutionId()).isEqualTo(TOOL_EXECUTION_ID);
        assertThat(tool.toolCallId()).isEqualTo(TOOL_CALL_ID);
        assertThat(tool.status()).isEqualTo(ThinkingProcessStatus.SUCCEEDED);
        assertThat(tool.durationMillis()).isEqualTo(5L);
        assertThat(json.valueToTree(tool.input().value()).path("token").asString())
                .isEqualTo(REDACTED_VALUE);
    }

    @Test
    void toolExecutionRoundTripKeepsItsProcessItemUuid() throws Exception {
        AgentExecutionPort.ClaimedAgentExecution run = createRunningExecution("tool-uuid");
        UUID itemId =
                TenantContext.call(
                        TENANT_ID,
                        () ->
                                coordinator.prepareTool(
                                        run,
                                        1L,
                                        new ModelToolCall(
                                                TOOL_CALL_ID, TOOL_NAME, json.readTree(EMPTY_JSON)),
                                        TOOL_EXECUTION_ID,
                                        STARTED_AT));

        // 工具执行必须关联一个已归档的模型调用边界，保持测试数据符合生产外键约束。
        jdbc.update(
                """
                insert into model_invocation(
                    model_invocation_id, tenant_id, execution_id, model_invocation_sequence,
                    execution_attempt_number, input_up_to_conversation_message_sequence,
                    status, started_at, completed_at)
                values (?, ?, ?, 1, 1, 1, 'completed', ?, ?)
                """,
                MODEL_INVOCATION_ID,
                TENANT_ID,
                run.executionId(),
                Timestamp.from(STARTED_AT),
                Timestamp.from(STARTED_AT.plusMillis(1)));

        // 按生产路径保存并重新查询工具执行，验证 UUID 不会在 MyBatis 结果映射时丢失。
        ToolExecutionEntity execution = new ToolExecutionEntity();
        execution.setToolExecutionId(TOOL_EXECUTION_ID);
        execution.setExecutionId(run.executionId());
        execution.setModelInvocationSequence(1L);
        execution.setExecutionAttemptNumber(run.executionAttemptNumber());
        execution.setAssistantConversationMessageId(
                assistant(run.executionId()).getConversationMessageId());
        execution.setToolProcessItemId(itemId);
        execution.setToolCallId(TOOL_CALL_ID);
        execution.setToolName(TOOL_NAME);
        execution.setStatus(TOOL_EXECUTION_STATUS_PREPARED);
        execution.setExecutionCount(0);
        execution.setVersion(0L);
        TenantContext.run(TENANT_ID, () -> toolExecutions.save(execution));

        ToolExecutionEntity reloaded =
                TenantContext.call(
                        TENANT_ID,
                        () -> toolExecutions.findByToolExecutionId(TOOL_EXECUTION_ID));
        assertThat(reloaded.getToolProcessItemId()).isEqualTo(itemId);
    }

    @Test
    void staleAttemptCannotAppendOrReplaceAProcessItem() throws Exception {
        AgentExecutionPort.ClaimedAgentExecution run = createRunningExecution("stale");
        UUID itemId =
                TenantContext.call(
                        TENANT_ID,
                        () ->
                                coordinator.startThinking(
                                        run,
                                        1L,
                                        MODEL_PROVIDER,
                                        MODEL_NAME,
                                        FIRST_THINKING_DELTA,
                                        STARTED_AT));
        String persisted = thinkingProcessDocument(run.executionId());
        AgentExecutionPort.ClaimedAgentExecution staleRun =
                new AgentExecutionPort.ClaimedAgentExecution(
                        TENANT_ID, run.executionId(), STALE_OWNER_ID, 0L);

        assertThatThrownBy(
                        () ->
                                TenantContext.run(
                                        TENANT_ID,
                                        () ->
                                                coordinator.appendThinking(
                                                        staleRun,
                                                        itemId,
                                                        SECOND_THINKING_DELTA,
                                                        STARTED_AT.plusMillis(1))))
                .isInstanceOf(ExecutionOwnershipLostException.class);
        assertThat(thinkingProcessDocument(run.executionId())).isEqualTo(persisted);
    }

    @Test
    void duplicateIdentityAndIllegalTerminalTransitionRollBackJsonb() throws Exception {
        AgentExecutionPort.ClaimedAgentExecution run = createRunningExecution("invalid");
        UUID itemId =
                TenantContext.call(
                        TENANT_ID,
                        () ->
                                coordinator.startThinking(
                                        run,
                                        1L,
                                        MODEL_PROVIDER,
                                        MODEL_NAME,
                                        FIRST_THINKING_DELTA,
                                        STARTED_AT));
        TenantContext.run(
                TENANT_ID,
                () ->
                        coordinator.finishThinking(
                                run,
                                itemId,
                                ThinkingProcessStatus.SUCCEEDED,
                                STARTED_AT.plusMillis(3)));
        String terminal = thinkingProcessDocument(run.executionId());

        assertThatThrownBy(
                        () ->
                                TenantContext.run(
                                        TENANT_ID,
                                        () ->
                                                coordinator.appendThinking(
                                                        run,
                                                        itemId,
                                                        SECOND_THINKING_DELTA,
                                                        STARTED_AT.plusMillis(4))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(thinkingProcessDocument(run.executionId())).isEqualTo(terminal);

        jdbc.update(
                "update conversation_message set thinking_process = thinking_process || thinking_process where execution_id = ?",
                run.executionId());
        String duplicate = thinkingProcessDocument(run.executionId());
        assertThatThrownBy(
                        () ->
                                TenantContext.run(
                                        TENANT_ID,
                                        () ->
                                                coordinator.startThinking(
                                                        run,
                                                        2L,
                                                        MODEL_PROVIDER,
                                                        MODEL_NAME,
                                                        FIRST_THINKING_DELTA,
                                                        STARTED_AT.plusMillis(5))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(thinkingProcessDocument(run.executionId())).isEqualTo(duplicate);
    }

    @Test
    void failOpenItemsClosesRunningThinkingAndPreparedToolNodes() throws Exception {
        AgentExecutionPort.ClaimedAgentExecution run = createRunningExecution("fail-open");
        TenantContext.call(
                TENANT_ID,
                () ->
                        coordinator.startThinking(
                                run,
                                1L,
                                MODEL_PROVIDER,
                                MODEL_NAME,
                                FIRST_THINKING_DELTA,
                                STARTED_AT));
        TenantContext.call(
                TENANT_ID,
                () ->
                        coordinator.prepareTool(
                                run,
                                1L,
                                new ModelToolCall(
                                        TOOL_CALL_ID,
                                        TOOL_NAME,
                                        json.readTree("{\"query\":\"select 1\"}")),
                                TOOL_EXECUTION_ID,
                                STARTED_AT.plusMillis(1)));

        TenantContext.run(
                TENANT_ID,
                () ->
                        coordinator.failOpenItems(
                                run.executionId(),
                                FAILURE_CODE,
                                FAILURE_MESSAGE,
                                STARTED_AT.plusMillis(6)));

        assertThat(assistant(run.executionId()).getThinkingProcess())
                .allSatisfy(
                        item -> assertThat(item.status()).isEqualTo(ThinkingProcessStatus.FAILED));
    }

    private ConversationMessage assistant(String executionId) {
        return TenantContext.call(
                TENANT_ID,
                () ->
                        messages.findByExecutionId(executionId).stream()
                                .filter(message -> "assistant".equals(message.getRole()))
                                .findFirst()
                                .orElseThrow());
    }

    private int messageCount(String executionId) {
        return jdbc.queryForObject(
                "select count(*) from conversation_message where execution_id = ? and role = 'assistant'",
                Integer.class,
                executionId);
    }

    private String thinkingProcessDocument(String executionId) {
        return jdbc.queryForObject(
                "select thinking_process::text from conversation_message where execution_id = ? and role = 'assistant'",
                String.class,
                executionId);
    }

    private AgentExecutionPort.ClaimedAgentExecution createRunningExecution(String suffix)
            throws Exception {
        Cookie login = login();
        String conversationSessionId = createSession(login, suffix);
        String body =
                mvc.perform(
                                post(
                                                "/api/v1/agent-sessions/{conversationSessionId}/messages",
                                                conversationSessionId)
                                        .cookie(login)
                                        .header("Idempotency-Key", "submit-" + suffix)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"content\":\"hello\"}"))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String executionId = json.readTree(body).path("execution").path("executionId").asString();
        jdbc.update(
                """
                update agent_execution set status='running', phase='RECEIVED', execution_attempt_number=1,
                  owner_instance_id=?, started_at=?, heartbeat_at=?, lease_expires_at=?,
                  active_segment_started_at=?, hard_deadline_at=? where execution_id=?
                """,
                OWNER_ID,
                Timestamp.from(STARTED_AT),
                Timestamp.from(STARTED_AT),
                Timestamp.from(STARTED_AT.plusSeconds(60)),
                Timestamp.from(STARTED_AT),
                Timestamp.from(STARTED_AT.plusSeconds(600)),
                executionId);
        return new AgentExecutionPort.ClaimedAgentExecution(TENANT_ID, executionId, OWNER_ID, 1L);
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
        return new Cookie(
                SESSION_COOKIE,
                setCookie.substring((SESSION_COOKIE + "=").length(), setCookie.indexOf(';')));
    }
}
