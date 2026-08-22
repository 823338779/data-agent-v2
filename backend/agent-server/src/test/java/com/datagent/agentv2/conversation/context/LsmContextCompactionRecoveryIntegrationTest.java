package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.context.lsm.persistence.repository.LsmExecutionPolicyRepository;
import com.datagent.agentv2.context.lsm.domain.LsmContextPolicySnapshot;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.PlannedCompaction;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.ReadyCompaction;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionPlanningTransaction;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionRecoveryService;
import com.datagent.agentv2.context.lsm.runtime.LsmConversationContextAssembler;
import com.datagent.agentv2.context.lsm.runtime.LsmManifestPublicationTransaction;
import com.datagent.agentv2.context.lsm.strategy.LsmConversationContextStrategy;
import com.datagent.agentv2.conversation.exception.ExecutionOwnershipLostException;
import com.datagent.agentv2.conversation.runtime.AgentExecutionLeaseService;
import com.datagent.agentv2.platform.context.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 使用真实 PostgreSQL 验收租约接管、候选复用、旧 owner fencing 与 Redis 降级。 */
@SpringBootTest(
        classes = LsmContextCompactionIntegrationTest.TestApplication.class,
        properties = {
            "data-agent.context.strategy=lsm",
            "data-agent.runtime.scheduling-enabled=false"
        })
class LsmContextCompactionRecoveryIntegrationTest {

    private static final String TENANT_ID = "tenant_recovery_acceptance";
    private static final String USER_ID = "user_recovery_acceptance";
    private static final String SESSION_ID = "session_recovery_acceptance";
    private static final String EXECUTION_ID = "execution_recovery_acceptance";
    private static final String OLD_OWNER_ID = "owner-old";
    private static final String NEW_OWNER_ID = "owner-new";
    private static final String HISTORY_PAYLOAD = "recovery payload ".repeat(24);
    private static final int COMPLETED_TURNS = 20;
    private static final long INPUT_BOUNDARY = COMPLETED_TURNS * 2L + 1L;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LsmExecutionPolicyRepository policies;
    @Autowired private LsmConversationContextAssembler assembler;
    @Autowired private LsmCompactionPlanningTransaction planning;
    @Autowired private LsmCompactionRecoveryService recovery;
    @Autowired private LsmManifestPublicationTransaction publication;
    @Autowired private LsmConversationContextStrategy strategy;
    @Autowired private AgentExecutionLeaseService leases;
    @Autowired private RedisConnectionFactory redis;
    @Autowired private LsmContextCompactionIntegrationTest.DeterministicSummaryModel summaryModel;

    @DynamicPropertySource
    static void runtimeProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url", LsmContextPostgresIntegrationSupport.POSTGRES::getJdbcUrl);
        registry.add(
                "spring.datasource.username",
                LsmContextPostgresIntegrationSupport.POSTGRES::getUsername);
        registry.add(
                "spring.datasource.password",
                LsmContextPostgresIntegrationSupport.POSTGRES::getPassword);
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> 1);
    }

    @BeforeEach
    void seed() {
        summaryModel.reset();
        jdbc.execute("truncate table tenant cascade");
        jdbc.update("insert into tenant (tenant_id, name) values (?, ?)", TENANT_ID, TENANT_ID);
        jdbc.update(
                "insert into user_account (user_id, tenant_id, username, password_hash) values (?,"
                        + " ?, ?, ?)",
                USER_ID,
                TENANT_ID,
                USER_ID,
                "unused");
        jdbc.update(
                "insert into conversation_session (conversation_session_id, tenant_id, title,"
                        + " session_type, created_by_user_id) values (?, ?, ?, ?, ?)",
                SESSION_ID,
                TENANT_ID,
                "Recovery acceptance",
                "chat",
                USER_ID);

        // 历史 execution/message 通过真实表建立，让 assembler 读取与生产相同的对话边界。
        for (int turn = 1; turn <= COMPLETED_TURNS; turn++) {
            String historyExecution = "history-execution-" + turn;
            long questionSequence = turn * 2L - 1L;
            long answerSequence = turn * 2L;
            String questionId = "message-" + questionSequence;
            insertMessage(
                    questionId,
                    "user",
                    "question-" + turn + " " + HISTORY_PAYLOAD,
                    questionSequence);
            insertExecution(historyExecution, questionId, null, 1L);
            insertMessage(
                    "message-" + answerSequence,
                    "assistant",
                    "answer-" + turn + " " + HISTORY_PAYLOAD,
                    answerSequence);
            jdbc.update(
                    "update conversation_message set execution_id = ? where"
                            + " conversation_message_id in (?, ?)",
                    historyExecution,
                    questionId,
                    "message-" + answerSequence);
        }

        String currentMessage = "message-" + INPUT_BOUNDARY;
        insertMessage(currentMessage, "user", "current recovery question", INPUT_BOUNDARY);
        insertExecution(EXECUTION_ID, currentMessage, OLD_OWNER_ID, 1L);
        jdbc.update(
                "insert into agent_execution_attempt (execution_attempt_id, tenant_id,"
                        + " execution_id, execution_attempt_number, owner_instance_id, status,"
                        + " started_at) values (?, ?, ?, 1, ?, 'running', ?)",
                "attempt-old",
                TENANT_ID,
                EXECUTION_ID,
                OLD_OWNER_ID,
                Timestamp.from(Instant.now().minusSeconds(1)));
    }

    @Test
    void leaseTakeoverReusesCandidateFencesOldOwnerAndPublishesWithoutRedis() {
        assertThat(redis).isNotNull();
        assertThatThrownBy(() -> redis.getConnection().ping()).isInstanceOf(RuntimeException.class);

        ConversationContextRequest oldRequest = triggerRequest(OLD_OWNER_ID, 1L);
        LsmContextPolicySnapshot frozen =
                TenantContext.call(
                        TENANT_ID,
                        () ->
                                policies.findOrCreateExecutionPolicy(
                                        oldRequest.execution(),
                                        new LsmContextPolicySnapshot(1, 3, 3, 10, 75, 65, 100, 109, 10)));

        // 旧 owner 真实持久化 PLANNED/READY job 与候选，但故意停在 manifest publication 前。
        PlannedCompaction oldPlanned =
                TenantContext.call(
                        TENANT_ID,
                        () ->
                                planning.begin(
                                        assembler.toCompactionRequest(
                                                assembler.loadAndEstimate(oldRequest, frozen))));
        ReadyCompaction oldReady = TenantContext.call(TENANT_ID, () -> recovery.resume(oldPlanned));
        assertThat(summaryModel.invocationCount()).isEqualTo(1);
        assertThat(jobStatus(1L)).isEqualTo("READY");
        assertThat(segmentCount()).isEqualTo(1);
        assertThat(manifestGeneration()).isZero();

        // 真实 lease takeover 将 execution owner 与 attempt 原子推进到新实例/attempt 2。
        jdbc.update(
                "update agent_execution set lease_expires_at = ? where execution_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)),
                EXECUTION_ID);
        AgentExecutionLeaseService.RecoveryResult takeover =
                TenantContext.call(TENANT_ID, () -> leases.takeoverNext(NEW_OWNER_ID));
        assertThat(takeover.execution()).isNotNull();
        assertThat(takeover.execution().ownerInstanceId()).isEqualTo(NEW_OWNER_ID);
        assertThat(takeover.execution().executionAttemptNumber()).isEqualTo(2L);

        ConversationContextRequest newRequest = triggerRequest(NEW_OWNER_ID, 2L);
        PreparedConversationContext published =
                TenantContext.call(TENANT_ID, () -> strategy.prepare(newRequest));
        PreparedConversationContext replayed =
                TenantContext.call(TENANT_ID, () -> strategy.prepare(newRequest));

        // attempt 2 用相同 segment key 复用旧候选，真实 coordinator 发布并可从 PostgreSQL 重放。
        assertThat(summaryModel.invocationCount()).isEqualTo(1);
        assertThat(segmentCount()).isEqualTo(1);
        assertThat(jobStatus(2L)).isEqualTo("PUBLISHED");
        assertThat(manifestGeneration()).isEqualTo(1L);
        assertThat(replayed.estimatedInputTokens()).isEqualTo(published.estimatedInputTokens());
        assertThat(executionOwner()).isEqualTo(NEW_OWNER_ID);

        assertThatThrownBy(() -> TenantContext.call(TENANT_ID, () -> publication.publish(oldReady)))
                .isInstanceOf(ExecutionOwnershipLostException.class);
        assertThat(jobStatus(1L)).isEqualTo("READY");
        assertThat(manifestGeneration()).isEqualTo(1L);
    }

    private ConversationContextRequest triggerRequest(String ownerId, long attempt) {
        ConversationContextRequest measurement =
                request(ownerId, attempt, new ContextBudget(1_000_000, 1, 1, 0));
        int actual =
                TenantContext.call(
                                TENANT_ID,
                                () ->
                                        assembler.loadAndEstimate(
                                                measurement,
                                                LsmContextPolicySnapshot.defaults(5, 4)))
                        .estimatedTokens();
        return request(ownerId, attempt, budgetWithSoftTrigger(actual - 1));
    }

    private static ConversationContextRequest request(
            String ownerId, long attempt, ContextBudget budget) {
        return new ConversationContextRequest(
                new ContextExecutionRef(
                        TENANT_ID, SESSION_ID, EXECUTION_ID, ownerId, attempt, INPUT_BOUNDARY),
                budget,
                List.of(),
                List.of());
    }

    private static ContextBudget budgetWithSoftTrigger(int expectedSoftTrigger) {
        int contextWindow = 1;
        while ((int) Math.floor(contextWindow * 0.75d) != expectedSoftTrigger) {
            contextWindow++;
        }
        return new ContextBudget(contextWindow, 1, 1, 0);
    }

    private void insertMessage(String messageId, String role, String content, long sequence) {
        jdbc.update(
                "insert into conversation_message (conversation_message_id, tenant_id,"
                        + " conversation_session_id, role, content, conversation_message_sequence,"
                        + " author_user_id) values (?, ?, ?, ?, ?, ?, ?)",
                messageId,
                TENANT_ID,
                SESSION_ID,
                role,
                content,
                sequence,
                "user".equals(role) ? USER_ID : null);
    }

    private void insertExecution(
            String executionId, String triggeringMessageId, String ownerId, long attempt) {
        Instant now = Instant.now();
        boolean running = ownerId != null;
        jdbc.update(
                "insert into agent_execution (execution_id, tenant_id, conversation_session_id,"
                        + " triggering_conversation_message_id, status, phase, execution_attempt_number,"
                        + " owner_user_id, owner_instance_id, request_id, queued_at, started_at,"
                        + " heartbeat_at, lease_expires_at, active_segment_started_at, hard_deadline_at,"
                        + " execution_budget_ms, active_execution_ms, recovery_attempt_count) values"
                        + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 600000, 0, 0)",
                executionId,
                TENANT_ID,
                SESSION_ID,
                triggeringMessageId,
                running ? "running" : "done",
                running ? "PREPARING_CONTEXT" : "COMPLETED",
                attempt,
                USER_ID,
                ownerId,
                "request-" + executionId,
                Timestamp.from(now.minusSeconds(2)),
                Timestamp.from(now.minusSeconds(1)),
                running ? Timestamp.from(now) : null,
                running ? Timestamp.from(now.plusSeconds(60)) : null,
                running ? Timestamp.from(now) : null,
                running ? Timestamp.from(now.plusSeconds(600)) : null);
        jdbc.update(
                "update conversation_message set execution_id = ? where conversation_message_id = ?",
                executionId,
                triggeringMessageId);
    }

    private String jobStatus(long attempt) {
        return jdbc.queryForObject(
                "select status from conversation_context_lsm_job where tenant_id = ? and"
                        + " execution_id = ? and execution_attempt = ?",
                String.class,
                TENANT_ID,
                EXECUTION_ID,
                attempt);
    }

    private int segmentCount() {
        return jdbc.queryForObject(
                "select count(*) from conversation_context_lsm_segment where tenant_id = ? and"
                        + " conversation_session_id = ?",
                Integer.class,
                TENANT_ID,
                SESSION_ID);
    }

    private long manifestGeneration() {
        return jdbc.queryForObject(
                "select generation from conversation_context_lsm_manifest where tenant_id = ? and"
                        + " conversation_session_id = ?",
                Long.class,
                TENANT_ID,
                SESSION_ID);
    }

    private String executionOwner() {
        return jdbc.queryForObject(
                "select owner_instance_id from agent_execution where tenant_id = ? and execution_id"
                        + " = ?",
                String.class,
                TENANT_ID,
                EXECUTION_ID);
    }
}

/** 三个 Task 11 PostgreSQL 场景共享一个容器和同一 Spring TestContext。 */
@SpringBootTest(
        classes = LsmContextPostgresIntegrationSupport.Application.class,
        properties = "spring.flyway.enabled=false")
abstract class LsmContextPostgresIntegrationSupport {

    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    static {
        POSTGRES.start();
        flyway("classpath:db/migration", "flyway_schema_history").migrate();
        flyway(
                        "classpath:db/agent-context-lsm/migration",
                        "flyway_agent_context_lsm_schema_history")
                .migrate();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    protected static Flyway flyway(String location, String historyTable) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(location)
                .table(historyTable)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }

    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @org.springframework.context.annotation.Import({
        com.datagent.agentv2.persistence.MybatisPlusConfiguration.class,
        com.datagent.agentv2.context.lsm.configuration.LsmMapperConfiguration.class,
        com.datagent.agentv2.context.lsm.persistence.repository.LsmSummarySegmentRepository.class,
        com.datagent.agentv2.context.lsm.persistence.repository.LsmContextManifestRepository.class,
        com.datagent.agentv2.context.lsm.persistence.repository.LsmCompactionJobRepository.class,
        com.datagent.agentv2.context.lsm.persistence.json.LsmDocumentCodec.class
    })
    static class Application {}
}
