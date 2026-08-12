package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.datagent.agentv2.context.lsm.configuration.LsmMapperConfiguration;
import com.datagent.agentv2.context.lsm.domain.ConversationTurn;
import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan;
import com.datagent.agentv2.context.lsm.domain.LsmContextManifest;
import com.datagent.agentv2.context.lsm.domain.LsmPlanningInput;
import com.datagent.agentv2.context.lsm.persistence.json.LsmDocumentCodec;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmCompactionJobRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmContextManifestRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmSummarySegmentRepository;
import com.datagent.agentv2.context.lsm.planning.ConversationTokenEstimator;
import com.datagent.agentv2.context.lsm.planning.LsmCompactionPlanner;
import com.datagent.agentv2.context.lsm.planning.LsmInvariantValidator;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.CandidateInput;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.CompactionRequest;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.JobStatus;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.PlannedCompaction;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.PublishResult;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.ReadyCompaction;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionRecoveryService;
import com.datagent.agentv2.context.lsm.runtime.LsmManifestPublicationTransaction;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryDocumentValidator;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator.GenerationRequest;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator.MessageCoverage;
import com.datagent.agentv2.context.lsm.summary.LsmSegmentKeyFactory;
import com.datagent.agentv2.persistence.MybatisPlusConfiguration;
import com.datagent.agentv2.platform.context.TenantContext;
import com.datagent.agentv2.platform.id.IdGenerator;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/** 使用真实 PostgreSQL 验收租约接管、候选复用、旧 owner fencing 与 Redis 降级。 */
class LsmContextCompactionRecoveryIntegrationTest extends LsmContextPostgresIntegrationSupport {

    private static final String TENANT_ID = "tenant_recovery_acceptance";
    private static final String USER_ID = "user_recovery_acceptance";
    private static final String SESSION_ID = "session_recovery_acceptance";
    private static final String EXECUTION_ID = "execution_recovery_acceptance";
    private static final String OLD_OWNER_ID = "owner-old";
    private static final String NEW_OWNER_ID = "owner-new";
    private static final String JOB_ID = "job_recovery_acceptance";
    private static final String EMPTY_ARRAY = "[]";
    private static final String EMPTY_OBJECT = "{}";
    private static final String ZERO_HASH =
            "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String UNUSED_PASSWORD = "unused";
    private static final String SESSION_TITLE = "Recovery acceptance";
    private static final String SESSION_TYPE = "chat";
    private static final String TEST_OPERATOR = "test";
    private static final String TRUNCATE_TENANT_SQL = "truncate table tenant cascade";
    private static final String INSERT_TENANT_SQL =
            "insert into tenant (tenant_id, name) values (?, ?)";
    private static final String INSERT_USER_SQL =
            "insert into user_account (user_id, tenant_id, username, password_hash) values (?, ?,"
                    + " ?, ?)";
    private static final String INSERT_SESSION_SQL =
            "insert into conversation_session (conversation_session_id, tenant_id, title,"
                    + " session_type, created_by_user_id) values (?, ?, ?, ?, ?)";
    private static final String INSERT_MANIFEST_SQL =
            "insert into conversation_context_lsm_manifest (context_manifest_id, tenant_id,"
                    + " conversation_session_id, active_segment_ids, warm_cutoff_turn_sequence,"
                    + " hot_from_turn_sequence, evaluated_up_to_message_sequence,"
                    + " effective_policy_version, effective_fanout, effective_max_level,"
                    + " generation, created_at, created_by, updated_at, updated_by, deleted,"
                    + " version) values (?, ?, ?, ?::jsonb, 0, 0, 0, 1, 3, 3, 0, ?, ?, ?, ?,"
                    + " false, 0)";
    private static final String INSERT_JOB_SQL =
            "insert into conversation_context_lsm_job (compaction_job_id, tenant_id,"
                    + " conversation_session_id, execution_id, execution_attempt,"
                    + " input_up_to_message_sequence, source_manifest_generation,"
                    + " source_manifest_version, policy_snapshot, plan_document, plan_sha256,"
                    + " status, owner_instance_id, created_at, created_by, updated_at, updated_by,"
                    + " deleted, version) values (?, ?, ?, ?, 2, 22, 0, 0, ?::jsonb, ?::jsonb, ?,"
                    + " 'PLANNED', ?, ?, 'test', ?, 'test', false, 0)";
    private static final String SEGMENT_COUNT_SQL =
            "select count(*) from conversation_context_lsm_segment where tenant_id = ? and"
                    + " conversation_session_id = ?";
    private static final String MANIFEST_GENERATION_SQL =
            "select generation from conversation_context_lsm_manifest where tenant_id = ? and"
                    + " conversation_session_id = ?";
    private static final String JOB_OWNER_SQL =
            "select owner_instance_id from conversation_context_lsm_job where tenant_id = ? and"
                    + " compaction_job_id = ?";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LsmSummarySegmentRepository segments;
    @Autowired private LsmContextManifestRepository manifests;
    @Autowired private LsmCompactionJobRepository jobs;
    @Autowired private LsmDocumentCodec documents;

    @BeforeEach
    void seed() {
        jdbc.execute(TRUNCATE_TENANT_SQL);
        jdbc.update(INSERT_TENANT_SQL, TENANT_ID, TENANT_ID);
        jdbc.update(INSERT_USER_SQL, USER_ID, TENANT_ID, USER_ID, UNUSED_PASSWORD);
        jdbc.update(INSERT_SESSION_SQL, SESSION_ID, TENANT_ID, SESSION_TITLE, SESSION_TYPE, USER_ID);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                INSERT_MANIFEST_SQL,
                "manifest_recovery_acceptance",
                TENANT_ID,
                SESSION_ID,
                EMPTY_ARRAY,
                now,
                TEST_OPERATOR,
                now,
                TEST_OPERATOR);
        jdbc.update(
                INSERT_JOB_SQL,
                JOB_ID,
                TENANT_ID,
                SESSION_ID,
                EXECUTION_ID,
                EMPTY_OBJECT,
                EMPTY_OBJECT,
                ZERO_HASH,
                OLD_OWNER_ID,
                now,
                now);
    }

    @Test
    void leaseTakeoverReusesCandidateFencesOldOwnerAndPublishesWithoutRedis() {
        ContextSummaryModelPort model = mock(ContextSummaryModelPort.class);
        org.mockito.Mockito.when(model.summarize(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ContextSummaryResult(validSummary(), 40, "fake", "deterministic"));
        ContextExecutionControlPort executionControl = mock(ContextExecutionControlPort.class);
        AtomicInteger idSequence = new AtomicInteger();
        IdGenerator ids = prefix -> prefix + "-" + idSequence.incrementAndGet();
        ContextSummaryGenerator generator =
                new ContextSummaryGenerator(
                        model,
                        new ContextSummaryDocumentValidator(),
                        new ConversationTokenEstimator(),
                        new LsmSegmentKeyFactory());
        LsmCompactionRecoveryService recovery =
                new LsmCompactionRecoveryService(
                        generator, segments, jobs, executionControl, documents, ids);
        LsmManifestPublicationTransaction publication =
                new LsmManifestPublicationTransaction(
                        manifests,
                        segments,
                        jobs,
                        executionControl,
                        new LsmInvariantValidator(),
                        documents);
        PlannedCompaction takeover = planned(NEW_OWNER_ID);

        ReadyCompaction ready = TenantContext.call(TENANT_ID, () -> recovery.resume(takeover));
        assertThat(TenantContext.call(TENANT_ID, () -> publication.publish(ready)))
                .isEqualTo(PublishResult.PUBLISHED);

        // Redis 不在该组件图中；候选和 manifest 仍可完全从 PostgreSQL 重放。
        ReadyCompaction replayed = TenantContext.call(TENANT_ID, () -> recovery.resume(takeover));
        assertThat(TenantContext.call(TENANT_ID, () -> publication.publish(replayed)))
                .isEqualTo(PublishResult.PUBLISHED);
        verify(model, times(1)).summarize(org.mockito.ArgumentMatchers.any());
        assertThat(segmentCount()).isEqualTo(1);
        assertThat(manifestGeneration()).isEqualTo(1L);

        ReadyCompaction staleOwner =
                new ReadyCompaction(planned(OLD_OWNER_ID), ready.plan(), ready.candidates(), false);
        assertThat(TenantContext.call(TENANT_ID, () -> publication.publish(staleOwner)))
                .isEqualTo(PublishResult.SUPERSEDED);
        assertThat(jobOwner()).isEqualTo(NEW_OWNER_ID);
        assertThat(manifestGeneration()).isEqualTo(1L);
    }

    private static PlannedCompaction planned(String ownerId) {
        ContextExecutionRef execution =
                new ContextExecutionRef(TENANT_ID, SESSION_ID, EXECUTION_ID, ownerId, 2L, 22L);
        ContextPolicySnapshot policy = new ContextPolicySnapshot(1, 3, 3, 10, 75, 65, 100, 109, 10);
        LsmPlanningInput input =
                new LsmPlanningInput(
                        completedTurns(11),
                        LsmContextManifest.empty(),
                        List.of(),
                        new LsmPlanningInput.Policy(1, 3, 3),
                        new LsmPlanningInput.TokenPressure(2_001, 1_300, 2_000),
                        true);
        LsmCompactionPlan plan = new LsmCompactionPlanner().plan(input);
        LsmCompactionPlan.PlannedOperation operation = plan.operations().getFirst();
        CandidateInput candidate =
                new CandidateInput(
                        operation.targetId(),
                        new GenerationRequest(
                                operation.targetKind(),
                                operation.targetLevel(),
                                operation.coverage(),
                                new MessageCoverage(1L, 2L),
                                List.of("raw-1-1"),
                                "[{\"role\":\"user\",\"content\":\"question-1\"}]"));
        CompactionRequest request =
                new CompactionRequest(execution, policy, input, List.of(candidate), old -> old);
        return new PlannedCompaction(JOB_ID, request, plan, 0L, 0L, JobStatus.PLANNED, false);
    }

    private static List<ConversationTurn> completedTurns(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(
                        sequence ->
                                new ConversationTurn(
                                        sequence,
                                        List.of(
                                                ContextMessage.user("question-" + sequence),
                                                ContextMessage.assistant(
                                                        "answer-" + sequence, null)),
                                        true))
                .toList();
    }

    private static String validSummary() {
        return """
        {"schemaVersion":1,"coverage":{"startTurnSequence":1,"endTurnSequence":1,"startMessageSequence":1,"endMessageSequence":2},"confirmedFacts":[],"userPreferences":[],"workingHypotheses":[],"decisions":[],"rejectedApproaches":[],"toolFindings":[],"openQuestions":[],"nextSteps":[]}
        """;
    }

    private int segmentCount() {
        return jdbc.queryForObject(SEGMENT_COUNT_SQL, Integer.class, TENANT_ID, SESSION_ID);
    }

    private long manifestGeneration() {
        return jdbc.queryForObject(MANIFEST_GENERATION_SQL, Long.class, TENANT_ID, SESSION_ID);
    }

    private String jobOwner() {
        return jdbc.queryForObject(JOB_OWNER_SQL, String.class, TENANT_ID, JOB_ID);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        MybatisPlusConfiguration.class,
        LsmMapperConfiguration.class,
        LsmSummarySegmentRepository.class,
        LsmContextManifestRepository.class,
        LsmCompactionJobRepository.class,
        LsmDocumentCodec.class
    })
    static class Application {}
}

/** 三个 Task 11 PostgreSQL 场景共享一个容器和同一 Spring TestContext。 */
@SpringBootTest(
        classes = LsmContextCompactionRecoveryIntegrationTest.Application.class,
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

    @org.springframework.test.context.DynamicPropertySource
    static void databaseProperties(
            org.springframework.test.context.DynamicPropertyRegistry registry) {
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
}
