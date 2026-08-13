package com.datagent.agentv2.context.hierarchical.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.context.hierarchical.configuration.HierarchicalMapperConfiguration;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalColdCandidate;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionJob;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalSummaryNode;
import com.datagent.agentv2.context.hierarchical.persistence.json.HierarchicalDocumentCodec;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.HierarchicalCompactionJobMapper;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCheckpointRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCompactionJobRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalExecutionPolicyRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalSummaryNodeRepository;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.persistence.MybatisPlusConfiguration;
import com.datagent.agentv2.platform.context.TenantContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import com.datagent.agentv2.platform.id.PrefixedUuidIdGenerator;
import java.time.LocalDateTime;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 使用真实 PostgreSQL 和 MyBatis 验证插件私有 repository 的并发与隔离边界。 */
@SpringBootTest(
        classes = HierarchicalRepositoryIntegrationTest.Application.class,
        properties = "spring.flyway.enabled=false")
class HierarchicalRepositoryIntegrationTest {

    private static final String TENANT_A = "tenant_a";
    private static final String TENANT_B = "tenant_b";
    private static final String SESSION_ID = "session_shared";
    private static final String USER_A = "user_a";
    private static final String EXECUTION_ID = "execution_hierarchical";
    private static final String OWNER_ID = "owner_hierarchical";
    private static final String OTHER_OWNER_ID = "owner_stale";
    private static final String JOB_ID = "job_hierarchical";

    /** 自然键重试使用不同的业务 ID，证明持久化行仍是唯一事实源。 */
    private static final String RETRY_JOB_ID = "job_retry";

    private static final String PRIMARY_HISTORY = "flyway_schema_history";
    private static final String HIERARCHICAL_HISTORY =
            "flyway_agent_context_hierarchical_schema_history";
    private static final String PRIMARY_MIGRATION_LOCATION = "classpath:db/migration";
    private static final String HIERARCHICAL_MIGRATION_LOCATION =
            "classpath:db/agent-context-hierarchical/migration";
    private static final String INITIAL_BASELINE_VERSION = "0";
    private static final String INSERT_TENANT_SQL =
            "insert into tenant (tenant_id, name) values (?, ?)";
    private static final String INSERT_USER_SQL =
            "insert into user_account (user_id, tenant_id, username, password_hash) values (?, ?, ?, ?)";
    private static final String INSERT_SESSION_SQL =
            "insert into conversation_session (conversation_session_id, tenant_id, title, session_type, created_by_user_id) values (?, ?, ?, ?, ?)";
    private static final String INSERT_JOB_SQL =
            "insert into conversation_context_hierarchical_compaction_job (hierarchical_compaction_job_id, tenant_id, conversation_session_id, execution_id, execution_attempt, owner_instance_id, input_up_to_message_sequence, base_checkpoint_version, base_strategy_generation, plan_document, status, created_at, created_by, updated_at, updated_by, deleted, version) values (?, ?, ?, ?, ?, ?, ?, 0, 1, ?::jsonb, ?, ?, ?, ?, ?, false, 0)";
    private static final String COUNT_NODES_SQL =
            "select count(*) from conversation_context_hierarchical_summary_node where tenant_id = ? and conversation_session_id = ?";
    private static final String COUNT_JOBS_SQL =
            "select count(*) from conversation_context_hierarchical_compaction_job where tenant_id = ? and execution_id = ? and execution_attempt = ? and input_up_to_message_sequence = ?";
    private static final String ADVANCE_JOB_BASE_SQL =
            "update conversation_context_hierarchical_compaction_job set base_checkpoint_version = ?, base_strategy_generation = ?, input_up_to_message_sequence = ? where tenant_id = ? and hierarchical_compaction_job_id = ?";
    private static final String UPDATE_JOB_VERSION_SQL =
            "update conversation_context_hierarchical_compaction_job set version = ? where tenant_id = ? and hierarchical_compaction_job_id = ?";
    private static final String SESSION_TITLE = "Hierarchical integration session";
    private static final String SESSION_TYPE = "chat";
    private static final String PASSWORD_HASH = "not-used";
    private static final String SYSTEM_OPERATOR = "hierarchical-test";
    private static final String EMPTY_FRONTIER = "{\"schemaVersion\":1,\"nodes\":[]}";
    private static final String SUMMARY_DOCUMENT = "{\"schemaVersion\":1,\"confirmedFacts\":[]}";

    /** 旧任务行仅用于验证升级后的自然键冲突读取兼容性。 */
    private static final String LEGACY_PLAN_V1_DOCUMENT = "{\"schemaVersion\":1,\"operations\":[]}";

    /** 新建任务必须使用当前 body-free plan schema。 */
    private static final String LEGACY_PLAN_V2_DOCUMENT =
            "{\"schemaVersion\":2,\"candidates\":[],\"coldCandidate\":null}";

    /** 未知未来 plan schema 不得作为新任务写入。 */
    private static final String PLAN_V3_DOCUMENT =
            "{\"schemaVersion\":3,\"warmTokenBudget\":600,\"candidates\":[],\"coldCandidate\":null}";

    private static final String PLAN_V4_DOCUMENT = "{\"schemaVersion\":4,\"operations\":[]}";

    /** 正文属性不得进入 durable plan。 */
    private static final String SUMMARY_DOCUMENT_FIELD = "summaryDocument";

    private static final String COLD_DOCUMENT =
            "{\"schemaVersion\":1,\"confirmedFacts\":[{\"statement\":\"cold fact\"}]}";

    /** JSONB 字段顺序不稳定，测试按 Jackson tree 比较语义等价性。 */
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    static {
        POSTGRES.start();
        flyway(PRIMARY_MIGRATION_LOCATION, PRIMARY_HISTORY).migrate();
        flyway(HIERARCHICAL_MIGRATION_LOCATION, HIERARCHICAL_HISTORY).migrate();
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private HierarchicalExecutionPolicyRepository policies;
    @Autowired private HierarchicalCheckpointRepository checkpoints;
    @Autowired private HierarchicalSummaryNodeRepository nodes;
    @Autowired private HierarchicalCompactionJobRepository jobs;
    @Autowired private HierarchicalCompactionJobMapper jobMapper;
    @Autowired private HierarchicalDocumentCodec documents;

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

    @BeforeEach
    void seedSession() {
        jdbc.execute("truncate table tenant cascade");
        jdbc.update(INSERT_TENANT_SQL, TENANT_A, "Tenant A");
        jdbc.update(INSERT_TENANT_SQL, TENANT_B, "Tenant B");
        jdbc.update(INSERT_USER_SQL, USER_A, TENANT_A, USER_A, PASSWORD_HASH);
        jdbc.update(INSERT_SESSION_SQL, SESSION_ID, TENANT_A, SESSION_TITLE, SESSION_TYPE, USER_A);
    }

    @Test
    void executionPolicyIsFrozenOnFirstUseAndTenantBound() {
        HierarchicalContextPolicySnapshot first = policy(0.75d, 0.70d);
        HierarchicalContextPolicySnapshot changed = policy(0.80d, 0.60d);

        TenantContext.run(TENANT_A, () -> policies.findOrCreate(execution(), first));

        assertThat(TenantContext.call(TENANT_A, () -> policies.findOrCreate(execution(), changed)))
                .isEqualTo(first);
        assertThatThrownBy(
                        () ->
                                TenantContext.call(
                                        TENANT_B, () -> policies.findOrCreate(execution(), first)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void checkpointCasRejectsPointerRegressionStaleVersionOwnerAndAttempt() {
        HierarchicalCheckpoint current =
                TenantContext.call(TENANT_A, () -> checkpoints.create(checkpoint(20L, 1L)));
        insertJob(HierarchicalCompactionJob.Status.READY_TO_PUBLISH);

        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () ->
                                        checkpoints.compareAndSetCheckpoint(
                                                current, checkpoint(10L, 2L), execution())))
                .isFalse();
        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () ->
                                        checkpoints.compareAndSetCheckpoint(
                                                current,
                                                checkpoint(30L, 2L),
                                                execution(OTHER_OWNER_ID, 1L))))
                .isFalse();
        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () ->
                                        checkpoints.compareAndSetCheckpoint(
                                                current,
                                                checkpoint(30L, 2L),
                                                execution(OWNER_ID, 2L))))
                .isFalse();
        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () ->
                                        checkpoints.compareAndSetCheckpoint(
                                                current, checkpoint(30L, 2L), execution())))
                .isTrue();
        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () ->
                                        checkpoints.compareAndSetCheckpoint(
                                                current, checkpoint(40L, 3L), execution())))
                .isFalse();
        assertThat(TenantContext.call(TENANT_A, () -> checkpoints.findCheckpoint(SESSION_ID)))
                .extracting(
                        HierarchicalCheckpoint::summarizedUpToConversationMessageSequence,
                        HierarchicalCheckpoint::strategyGeneration,
                        HierarchicalCheckpoint::version)
                .containsExactly(30L, 2L, 1L);
        assertThat(TenantContext.call(TENANT_B, () -> checkpoints.findCheckpoint(SESSION_ID)))
                .isNull();
    }

    @Test
    void checkpointCasAllowsFrontierPublicationWithoutColdPointerMovement() {
        HierarchicalCheckpoint current =
                TenantContext.call(TENANT_A, () -> checkpoints.create(checkpoint(20L, 1L)));
        insertJob(HierarchicalCompactionJob.Status.READY_TO_PUBLISH);
        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () ->
                                        checkpoints.compareAndSetCheckpoint(
                                                current, checkpoint(30L, 2L), execution())))
                .isTrue();
        HierarchicalCheckpoint published =
                TenantContext.call(TENANT_A, () -> checkpoints.findCheckpoint(SESSION_ID));
        jdbc.update(ADVANCE_JOB_BASE_SQL, 1L, 2L, 51L, TENANT_A, JOB_ID);

        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () ->
                                        checkpoints.compareAndSetCheckpoint(
                                                published,
                                                checkpoint(30L, 3L),
                                                execution(OWNER_ID, 1L, 51L))))
                .isTrue();
    }

    @Test
    void checkpointCasRejectsPointerBeyondFrozenExecutionBoundary() {
        HierarchicalCheckpoint current =
                TenantContext.call(TENANT_A, () -> checkpoints.create(checkpoint(20L, 1L)));
        insertJob(HierarchicalCompactionJob.Status.READY_TO_PUBLISH);

        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () ->
                                        checkpoints.compareAndSetCheckpoint(
                                                current, checkpoint(51L, 2L), execution())))
                .isFalse();
    }

    @Test
    void duplicateImmutableNodesReturnTheFirstPersistedNode() {
        HierarchicalSummaryNode first = node("node_first");
        HierarchicalSummaryNode duplicate = node("node_duplicate");

        HierarchicalSummaryNode persisted =
                TenantContext.call(TENANT_A, () -> nodes.insertOrSelect(first));
        HierarchicalSummaryNode repeated =
                TenantContext.call(TENANT_A, () -> nodes.insertOrSelect(duplicate));

        assertThat(persisted.nodeId()).isEqualTo(first.nodeId());
        assertThat(repeated.nodeId()).isEqualTo(first.nodeId());
        assertThat(jdbc.queryForObject(COUNT_NODES_SQL, Integer.class, TENANT_A, SESSION_ID))
                .isEqualTo(1);
    }

    @Test
    void generatedNodeFirstWriteIsAtomicallyRejectedAfterTakeover() {
        insertJob(HierarchicalCompactionJob.Status.GENERATING, PLAN_V3_DOCUMENT);
        HierarchicalCompactionJob generatedUnderOldOwner =
                TenantContext.call(TENANT_A, () -> jobs.findJob(JOB_ID));
        ContextExecutionRef newOwner = execution(OTHER_OWNER_ID, 2L);

        // 模型结果已生成但尚未 insert 时发生 claim，随后旧 owner 不得成为 immutable node 首个写入者。
        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () -> jobs.claimRecoverable(generatedUnderOldOwner, newOwner)))
                .isTrue();
        assertThatThrownBy(
                        () ->
                                TenantContext.call(
                                        TENANT_A,
                                        () ->
                                                nodes.insertOrSelectFenced(
                                                        JOB_ID, execution(), node("node_stale"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Hierarchical summary node write lost its job fence");
        assertThat(jdbc.queryForObject(COUNT_NODES_SQL, Integer.class, TENANT_A, SESSION_ID))
                .isZero();

        // 新 owner 仍可持久化相同有效结果；旧 owner 之后也不能借唯一键冲突伪装写入成功。
        assertThat(
                        TenantContext.call(
                                        TENANT_A,
                                        () ->
                                                nodes.insertOrSelectFenced(
                                                        JOB_ID, newOwner, node("node_new_owner")))
                                .nodeId())
                .isEqualTo("node_new_owner");
        assertThatThrownBy(
                        () ->
                                TenantContext.call(
                                        TENANT_A,
                                        () ->
                                                nodes.insertOrSelectFenced(
                                                        JOB_ID, execution(), node("node_stale"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Hierarchical summary node write lost its job fence");
    }

    @Test
    void summaryNodeIdReadsStayInsideRequiredTenantAndConversationSession() {
        HierarchicalSummaryNode persisted =
                TenantContext.call(TENANT_A, () -> nodes.insertOrSelect(node("node_first")));

        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () ->
                                        nodes.findByIds(
                                                SESSION_ID,
                                                List.of("missing", persisted.nodeId()))))
                .extracting(HierarchicalSummaryNode::nodeId)
                .containsExactly(persisted.nodeId());
        assertThat(
                        TenantContext.call(
                                TENANT_B,
                                () -> nodes.findByIds(SESSION_ID, List.of(persisted.nodeId()))))
                .isEmpty();
    }

    @Test
    void summaryNodeRejectsUnsupportedSchemaVersionEvenWhenDocumentMatchesCandidate() {
        HierarchicalSummaryNode unsupported =
                node("node_schema_unsupported", 99, 1, summaryDocument(99));

        assertThatThrownBy(
                        () -> TenantContext.call(TENANT_A, () -> nodes.insertOrSelect(unsupported)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void summaryNodeRejectsUnsupportedPromptVersion() {
        HierarchicalSummaryNode unsupported =
                node("node_prompt_unsupported", 1, 99, SUMMARY_DOCUMENT);

        assertThatThrownBy(
                        () -> TenantContext.call(TENANT_A, () -> nodes.insertOrSelect(unsupported)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void compactionJobRetryWithDifferentIdReturnsExistingNaturalKeyJob() {
        HierarchicalCompactionJob first = job(JOB_ID);
        HierarchicalCompactionJob retry = job(RETRY_JOB_ID);

        HierarchicalCompactionJob persisted =
                TenantContext.call(TENANT_A, () -> jobs.insertOrSelect(first));
        HierarchicalCompactionJob repeated =
                TenantContext.call(TENANT_A, () -> jobs.insertOrSelect(retry));

        assertThat(persisted.jobId()).isEqualTo(JOB_ID);
        assertThat(repeated.jobId()).isEqualTo(JOB_ID);
        assertThat(
                        jdbc.queryForObject(
                                COUNT_JOBS_SQL, Integer.class, TENANT_A, EXECUTION_ID, 1L, 50L))
                .isEqualTo(1);
    }

    @Test
    void compactionJobPersistsAndReadsV3WarmBudgetPlanUnchanged() {
        HierarchicalCompactionJob candidate = job(JOB_ID);

        HierarchicalCompactionJob persisted =
                TenantContext.call(TENANT_A, () -> jobs.insertOrSelect(candidate));
        HierarchicalCompactionJob reloaded =
                TenantContext.call(TENANT_A, () -> jobs.findJob(JOB_ID));

        // PostgreSQL jsonb may reorder fields, so equality is asserted on the JSON tree.
        assertThat(documentsEqual(persisted.planDocument(), PLAN_V3_DOCUMENT)).isTrue();
        assertThat(documentsEqual(reloaded.planDocument(), PLAN_V3_DOCUMENT)).isTrue();
        assertThat(reloaded.planDocument()).doesNotContain(SUMMARY_DOCUMENT_FIELD);
    }

    @Test
    void compactionJobNaturalKeyCollisionReturnsExistingLegacyV1Plan() {
        insertJob(HierarchicalCompactionJob.Status.PLANNED);

        HierarchicalCompactionJob collided =
                TenantContext.call(TENANT_A, () -> jobs.insertOrSelect(job(RETRY_JOB_ID)));

        // Planning can now observe this v1 document and emit its stable replan-required result.
        assertThat(collided.jobId()).isEqualTo(JOB_ID);
        assertThat(documentsEqual(collided.planDocument(), LEGACY_PLAN_V1_DOCUMENT)).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                COUNT_JOBS_SQL, Integer.class, TENANT_A, EXECUTION_ID, 1L, 50L))
                .isEqualTo(1);
    }

    @Test
    void compactionJobNaturalKeyCollisionReturnsExistingLegacyV2Plan() {
        insertJob(HierarchicalCompactionJob.Status.PLANNED, LEGACY_PLAN_V2_DOCUMENT);

        HierarchicalCompactionJob collided =
                TenantContext.call(TENANT_A, () -> jobs.insertOrSelect(job(RETRY_JOB_ID)));

        assertThat(collided.jobId()).isEqualTo(JOB_ID);
        assertThat(documentsEqual(collided.planDocument(), LEGACY_PLAN_V2_DOCUMENT)).isTrue();
    }

    @Test
    void compactionJobRejectsUnknownFuturePlanVersionForNewWrite() {
        HierarchicalCompactionJob unknown = job(JOB_ID, PLAN_V4_DOCUMENT);

        assertThatThrownBy(() -> TenantContext.call(TENANT_A, () -> jobs.insertOrSelect(unknown)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        jdbc.queryForObject(
                                COUNT_JOBS_SQL, Integer.class, TENANT_A, EXECUTION_ID, 1L, 50L))
                .isZero();
    }

    @Test
    void jobTransitionRequiresExpectedStatusVersionOwnerAndAttempt() {
        insertJob(HierarchicalCompactionJob.Status.PLANNED);

        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () ->
                                        jobs.transitionJob(
                                                JOB_ID,
                                                HierarchicalCompactionJob.Status.GENERATING,
                                                HierarchicalCompactionJob.Status.READY_TO_PUBLISH,
                                                execution(),
                                                null)))
                .isFalse();
        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () ->
                                        jobs.transitionJob(
                                                JOB_ID,
                                                HierarchicalCompactionJob.Status.PLANNED,
                                                HierarchicalCompactionJob.Status.GENERATING,
                                                execution(OTHER_OWNER_ID, 1L),
                                                null)))
                .isFalse();
        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () ->
                                        jobs.transitionJob(
                                                JOB_ID,
                                                HierarchicalCompactionJob.Status.PLANNED,
                                                HierarchicalCompactionJob.Status.GENERATING,
                                                execution(OWNER_ID, 2L),
                                                null)))
                .isFalse();
        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () ->
                                        jobs.transitionJob(
                                                JOB_ID,
                                                HierarchicalCompactionJob.Status.PLANNED,
                                                HierarchicalCompactionJob.Status.GENERATING,
                                                execution(),
                                                null)))
                .isTrue();
        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () ->
                                        jobs.transitionJob(
                                                JOB_ID,
                                                HierarchicalCompactionJob.Status.PLANNED,
                                                HierarchicalCompactionJob.Status.GENERATING,
                                                execution(),
                                                null)))
                .isFalse();
        assertThat(TenantContext.call(TENANT_A, () -> jobs.findJob(JOB_ID)))
                .extracting(HierarchicalCompactionJob::status, HierarchicalCompactionJob::version)
                .containsExactly(HierarchicalCompactionJob.Status.GENERATING, 1L);
    }

    @Test
    void jobTransitionSqlRejectsStaleVersionWhenAllOtherFencesMatch() {
        insertJob(HierarchicalCompactionJob.Status.PLANNED);
        jdbc.update(UPDATE_JOB_VERSION_SQL, 1L, TENANT_A, JOB_ID);
        LocalDateTime now = LocalDateTime.now();

        int transitioned =
                TenantContext.call(
                        TENANT_A,
                        () ->
                                jobMapper.transitionJob(
                                        TENANT_A,
                                        JOB_ID,
                                        HierarchicalCompactionJob.Status.PLANNED.name(),
                                        HierarchicalCompactionJob.Status.GENERATING.name(),
                                        0L,
                                        execution(),
                                        null,
                                        null,
                                        now,
                                        SYSTEM_OPERATOR));

        assertThat(transitioned).isZero();
        assertThat(TenantContext.call(TENANT_A, () -> jobs.findJob(JOB_ID)))
                .extracting(HierarchicalCompactionJob::status, HierarchicalCompactionJob::version)
                .containsExactly(HierarchicalCompactionJob.Status.PLANNED, 1L);
    }

    @Test
    void coldCandidateWriteIsFencedIdempotentAndRecoverable() {
        insertJob(HierarchicalCompactionJob.Status.GENERATING);
        HierarchicalColdCandidate candidate = coldCandidate(COLD_DOCUMENT);

        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () -> jobs.storeColdCandidate(JOB_ID, execution(), candidate)))
                .isTrue();
        assertThat(TenantContext.call(TENANT_A, () -> jobs.findColdCandidate(JOB_ID)))
                .isEqualTo(candidate);
        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () -> jobs.storeColdCandidate(JOB_ID, execution(), candidate)))
                .isTrue();
        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () ->
                                        jobs.storeColdCandidate(
                                                JOB_ID,
                                                execution(),
                                                coldCandidate(
                                                        COLD_DOCUMENT.replace(
                                                                "cold fact", "conflict")))))
                .isFalse();
        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () ->
                                        jobs.storeColdCandidate(
                                                JOB_ID, execution(OTHER_OWNER_ID, 1L), candidate)))
                .isFalse();
        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () ->
                                        jobs.storeColdCandidate(
                                                JOB_ID, execution(OWNER_ID, 2L), candidate)))
                .isFalse();
        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () ->
                                        jobs.storeColdCandidate(
                                                JOB_ID, execution(OWNER_ID, 1L, 51L), candidate)))
                .isFalse();
        assertThat(TenantContext.call(TENANT_A, () -> jobs.findColdCandidate(JOB_ID)))
                .isEqualTo(candidate);
    }

    @Test
    void coldCandidateWriteRejectsJobsOutsideGeneratingStatus() {
        insertJob(HierarchicalCompactionJob.Status.PLANNED);

        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () ->
                                        jobs.storeColdCandidate(
                                                JOB_ID, execution(), coldCandidate(COLD_DOCUMENT))))
                .isFalse();
        assertThat(TenantContext.call(TENANT_A, () -> jobs.findColdCandidate(JOB_ID))).isNull();
    }

    @Test
    void codecRejectsMalformedDocumentsAndUnknownSchemaVersions() {
        assertThatThrownBy(() -> documents.readPolicy("{not-json"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                documents.readPolicy(
                                        "{\"policyVersion\":99,\"triggerRatio\":0.75,\"targetRatio\":0.70,\"recentRawTurns\":10,\"warmWindowTurns\":100,\"summarySchemaVersion\":1,\"summaryPromptVersion\":1}"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                documents.readPolicy(
                                        "{\"policyVersion\":1,\"triggerRatio\":0.75,\"targetRatio\":0.70,\"recentRawTurns\":10,\"warmWindowTurns\":100,\"summarySchemaVersion\":99,\"summaryPromptVersion\":1}"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () -> documents.canonicalizeVersionedDocument("{\"schemaVersion\":99}", 1))
                .isInstanceOf(IllegalStateException.class);
    }

    private void insertJob(HierarchicalCompactionJob.Status status) {
        insertJob(status, LEGACY_PLAN_V1_DOCUMENT);
    }

    private void insertJob(HierarchicalCompactionJob.Status status, String planDocument) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                INSERT_JOB_SQL,
                JOB_ID,
                TENANT_A,
                SESSION_ID,
                EXECUTION_ID,
                1L,
                OWNER_ID,
                50L,
                planDocument,
                status.name(),
                now,
                SYSTEM_OPERATOR,
                now,
                SYSTEM_OPERATOR);
    }

    private static ContextExecutionRef execution() {
        return execution(OWNER_ID, 1L);
    }

    private static ContextExecutionRef execution(String ownerId, long attempt) {
        return execution(ownerId, attempt, 50L);
    }

    private static ContextExecutionRef execution(String ownerId, long attempt, long inputBoundary) {
        return new ContextExecutionRef(
                TENANT_A, SESSION_ID, EXECUTION_ID, ownerId, attempt, inputBoundary);
    }

    private static HierarchicalContextPolicySnapshot policy(double trigger, double target) {
        return new HierarchicalContextPolicySnapshot(1, trigger, target, 10, 100, 1, 1);
    }

    private static HierarchicalCheckpoint checkpoint(long pointer, long generation) {
        return new HierarchicalCheckpoint(
                "checkpoint_current",
                SESSION_ID,
                SUMMARY_DOCUMENT,
                pointer,
                1L,
                10,
                1,
                1,
                "integration-provider",
                "integration-model",
                EMPTY_FRONTIER,
                generation,
                0L);
    }

    private static HierarchicalSummaryNode node(String nodeId) {
        return node(nodeId, 1, 1, SUMMARY_DOCUMENT);
    }

    private static HierarchicalSummaryNode node(
            String nodeId, int schemaVersion, int promptVersion, String summaryDocument) {
        return new HierarchicalSummaryNode(
                nodeId,
                SESSION_ID,
                0,
                1L,
                1L,
                2L,
                null,
                null,
                summaryDocument,
                10,
                schemaVersion,
                promptVersion,
                "integration-provider",
                "integration-model");
    }

    private static HierarchicalCompactionJob job(String jobId) {
        return job(jobId, PLAN_V3_DOCUMENT);
    }

    private static HierarchicalCompactionJob job(String jobId, String planDocument) {
        return new HierarchicalCompactionJob(
                jobId,
                SESSION_ID,
                EXECUTION_ID,
                1L,
                OWNER_ID,
                50L,
                0L,
                1L,
                planDocument,
                HierarchicalCompactionJob.Status.PLANNED,
                null,
                0L);
    }

    /** 比较经过 PostgreSQL JSONB 往返后的文档语义。 */
    private static boolean documentsEqual(String left, String right) {
        try {
            return JSON.readTree(left).equals(JSON.readTree(right));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Invalid integration-test JSON document", failure);
        }
    }

    private static HierarchicalColdCandidate coldCandidate(String document) {
        return new HierarchicalColdCandidate(
                "cold-candidate-1",
                1L,
                2L,
                1L,
                20L,
                document,
                100,
                1,
                1,
                "integration-provider",
                "integration-model");
    }

    private static String summaryDocument(int schemaVersion) {
        return "{\"schemaVersion\":%d,\"confirmedFacts\":[]}".formatted(schemaVersion);
    }

    private static Flyway flyway(String location, String historyTable) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(location)
                .table(historyTable)
                .baselineOnMigrate(true)
                .baselineVersion(INITIAL_BASELINE_VERSION)
                .load();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        MybatisPlusConfiguration.class,
        HierarchicalMapperConfiguration.class,
        HierarchicalExecutionPolicyRepository.class,
        HierarchicalCheckpointRepository.class,
        HierarchicalSummaryNodeRepository.class,
        HierarchicalCompactionJobRepository.class,
        HierarchicalDocumentCodec.class,
        InfrastructureConfiguration.class
    })
    static class Application {}

    @TestConfiguration(proxyBeanMethods = false)
    static class InfrastructureConfiguration {

        @Bean
        IdGenerator idGenerator() {
            return new PrefixedUuidIdGenerator();
        }
    }
}
