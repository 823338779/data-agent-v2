package com.datagent.agentv2.context.lsm.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.context.lsm.configuration.LsmMapperConfiguration;
import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan;
import com.datagent.agentv2.context.lsm.domain.LsmContextManifest;
import com.datagent.agentv2.context.lsm.domain.LsmPlanningInput;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmCompactionJobEntity;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmContextManifestEntity;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmSummarySegmentEntity;
import com.datagent.agentv2.context.lsm.persistence.json.LsmDocumentCodec;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmCompactionJobRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmContextManifestRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmExecutionPolicyRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmSummarySegmentRepository;
import com.datagent.agentv2.context.lsm.planning.ConversationTokenEstimator;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.CandidateInput;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.CompactionRequest;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.JobStatus;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.PlannedCompaction;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionRecoveryService;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryDocumentValidator;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator.GeneratedSummary;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator.GenerationRequest;
import com.datagent.agentv2.context.lsm.summary.ContextSummaryGenerator.MessageCoverage;
import com.datagent.agentv2.context.lsm.summary.LsmSegmentKeyFactory;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextPolicySnapshot;
import com.datagent.agentv2.conversation.context.ContextSummaryResult;
import com.datagent.agentv2.persistence.MybatisPlusConfiguration;
import com.datagent.agentv2.platform.context.TenantContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import com.datagent.agentv2.platform.id.PrefixedUuidIdGenerator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
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

/** 使用真实 PostgreSQL、Flyway 和 MyBatis Mapper 验证插件 repository 的持久化语义。 */
@SpringBootTest(
        classes = LsmRepositoryIntegrationTest.Application.class,
        properties = "spring.flyway.enabled=false")
class LsmRepositoryIntegrationTest {

    private static final String TENANT_A = "tenant_a";
    private static final String TENANT_B = "tenant_b";
    private static final String SESSION_ID = "session_shared";
    private static final String USER_A = "user_a";
    private static final String SEGMENT_KEY =
            "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String CONTENT_HASH =
            "1111111111111111111111111111111111111111111111111111111111111111";
    private static final String EMPTY_ARRAY_JSON = "[]";
    private static final String EMPTY_OBJECT_JSON = "{}";
    private static final String STANDARD_KIND = "STANDARD";
    private static final String SYSTEM_OPERATOR = "lsm-test";
    private static final String PRIMARY_HISTORY = "flyway_schema_history";
    private static final String LSM_HISTORY = "flyway_agent_context_lsm_schema_history";
    private static final String PRIMARY_MIGRATION_LOCATION = "classpath:db/migration";
    private static final String LSM_MIGRATION_LOCATION = "classpath:db/agent-context-lsm/migration";
    private static final String INITIAL_BASELINE_VERSION = "0";
    private static final String INSERT_TENANT_SQL =
            "insert into tenant (tenant_id, name) values (?, ?)";
    private static final String INSERT_USER_SQL =
            "insert into user_account (user_id, tenant_id, username, password_hash) values (?, ?, ?, ?)";
    private static final String INSERT_SESSION_SQL =
            "insert into conversation_session (conversation_session_id, tenant_id, title, session_type, created_by_user_id) values (?, ?, ?, ?, ?)";
    private static final String INSERT_MANIFEST_SQL =
            "insert into conversation_context_lsm_manifest (context_manifest_id, tenant_id, conversation_session_id, active_segment_ids, warm_cutoff_turn_sequence, hot_from_turn_sequence, evaluated_up_to_message_sequence, effective_policy_version, effective_fanout, effective_max_level, generation, created_at, created_by, updated_at, updated_by, deleted, version) values (?, ?, ?, ?::jsonb, 0, 0, 0, 1, 3, 1, ?, ?, ?, ?, ?, false, ?)";
    private static final String COUNT_SEGMENTS_SQL =
            "select count(*) from conversation_context_lsm_segment where tenant_id = ? and conversation_session_id = ? and segment_key = ?";
    private static final String SESSION_TITLE = "LSM integration session";
    private static final String SESSION_TYPE = "chat";
    private static final String PASSWORD_HASH = "not-used";
    private static final String SOURCE_DOCUMENT = "{\"messages\":[]}";
    private static final String SOURCE_ID = "raw-1";
    private static final String MODEL_PROVIDER = "integration-provider";
    private static final String MODEL_NAME = "integration-model";
    private static final String EXECUTION_ID = "execution_integration";
    private static final String OWNER_ID = "owner_integration";
    private static final String JOB_ID = "job_integration";
    private static final String PLANNED_CHILD_ID = "planned-child";
    private static final String PLANNED_PARENT_ID = "planned-parent";
    private static final String COUNT_ALL_SEGMENTS_SQL =
            "select count(*) from conversation_context_lsm_segment where tenant_id = ? and conversation_session_id = ?";
    private static final String FIND_LEVEL_SEGMENT_ID_SQL =
            "select summary_segment_id from conversation_context_lsm_segment where tenant_id = ? and conversation_session_id = ? and level = ?";
    private static final String FIND_LEVEL_SOURCE_ID_SQL =
            "select source_segment_ids ->> 0 from conversation_context_lsm_segment where tenant_id = ? and conversation_session_id = ? and level = ?";
    private static final String FIND_LEVEL_SOURCE_COUNT_SQL =
            "select source_segment_count from conversation_context_lsm_segment where tenant_id = ? and conversation_session_id = ? and level = ?";
    private static final String VALID_SUMMARY =
            "{\"schemaVersion\":1,\"coverage\":{\"startTurnSequence\":1,\"endTurnSequence\":1,\"startMessageSequence\":1,\"endMessageSequence\":2},\"confirmedFacts\":[],\"userPreferences\":[],\"workingHypotheses\":[],\"decisions\":[],\"rejectedApproaches\":[],\"toolFindings\":[],\"openQuestions\":[],\"nextSteps\":[]}";

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    static {
        POSTGRES.start();
        flyway(PRIMARY_MIGRATION_LOCATION, PRIMARY_HISTORY).migrate();
        flyway(LSM_MIGRATION_LOCATION, LSM_HISTORY).migrate();
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LsmSummarySegmentRepository segments;
    @Autowired private LsmContextManifestRepository manifests;
    @Autowired private LsmExecutionPolicyRepository executionPolicies;

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
    void seedSessions() {
        jdbc.execute("truncate table tenant cascade");
        jdbc.update(INSERT_TENANT_SQL, TENANT_A, "Tenant A");
        jdbc.update(INSERT_TENANT_SQL, TENANT_B, "Tenant B");
        jdbc.update(INSERT_USER_SQL, USER_A, TENANT_A, USER_A, PASSWORD_HASH);
        jdbc.update(INSERT_SESSION_SQL, SESSION_ID, TENANT_A, SESSION_TITLE, SESSION_TYPE, USER_A);
    }

    @Test
    void springConstructsExecutionPolicyRepositoryWithItsRuntimeDependencies() {
        assertThat(executionPolicies).isNotNull();
    }

    @Test
    void segmentReadsAreTenantIsolatedAndSegmentKeyWritesAreIdempotent() {
        LsmSummarySegmentEntity first = segment("segment_first");
        LsmSummarySegmentEntity duplicate = segment("segment_duplicate");

        LsmSummarySegmentEntity persisted =
                TenantContext.call(TENANT_A, () -> segments.findOrCreate(first));
        LsmSummarySegmentEntity repeated =
                TenantContext.call(TENANT_A, () -> segments.findOrCreate(duplicate));

        assertThat(persisted.getSummarySegmentId()).isEqualTo(first.getSummarySegmentId());
        assertThat(repeated.getSummarySegmentId()).isEqualTo(first.getSummarySegmentId());
        assertThat(
                        TenantContext.call(
                                TENANT_A, () -> segments.findBySegmentKey(SESSION_ID, SEGMENT_KEY)))
                .hasValueSatisfying(
                        segment ->
                                assertThat(segment.getSummarySegmentId())
                                        .isEqualTo(first.getSummarySegmentId()));
        assertThat(
                        TenantContext.call(
                                TENANT_B, () -> segments.findBySegmentKey(SESSION_ID, SEGMENT_KEY)))
                .isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                COUNT_SEGMENTS_SQL,
                                Integer.class,
                                TENANT_A,
                                SESSION_ID,
                                SEGMENT_KEY))
                .isEqualTo(1);
    }

    @Test
    void taskSevenGeneratedSegmentFitsSchemaAndPersistsIdempotently() {
        ContextSummaryGenerator generator =
                new ContextSummaryGenerator(
                        request ->
                                new ContextSummaryResult(
                                        VALID_SUMMARY, 100, MODEL_PROVIDER, MODEL_NAME),
                        new ContextSummaryDocumentValidator(),
                        new ConversationTokenEstimator(),
                        new LsmSegmentKeyFactory());
        GenerationRequest request =
                new GenerationRequest(
                        LsmSummarySegment.SegmentKind.STANDARD,
                        0,
                        new LsmSummarySegment.Coverage(1, 1),
                        new MessageCoverage(1, 2),
                        java.util.List.of(SOURCE_ID),
                        SOURCE_DOCUMENT);
        GeneratedSummary generated = generator.generate(request);
        LsmSummarySegmentEntity first = generatedSegment("segment_generated_first", generated);
        LsmSummarySegmentEntity duplicate =
                generatedSegment("segment_generated_duplicate", generated);

        LsmSummarySegmentEntity persisted =
                TenantContext.call(TENANT_A, () -> segments.findOrCreate(first));
        LsmSummarySegmentEntity repeated =
                TenantContext.call(TENANT_A, () -> segments.findOrCreate(duplicate));

        assertThat(generated.segmentKey()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(repeated.getSummarySegmentId()).isEqualTo(persisted.getSummarySegmentId());
        assertThat(
                        jdbc.queryForObject(
                                COUNT_SEGMENTS_SQL,
                                Integer.class,
                                TENANT_A,
                                SESSION_ID,
                                generated.segmentKey()))
                .isEqualTo(1);
    }

    @Test
    void cascadedCandidatesPersistResolvedDatabaseLineage() {
        AtomicInteger generatedIds = new AtomicInteger();
        ContextSummaryGenerator generator = integrationGenerator();
        LsmCompactionJobRepository jobs =
                org.mockito.Mockito.mock(LsmCompactionJobRepository.class);
        ContextExecutionControlPort executions =
                org.mockito.Mockito.mock(ContextExecutionControlPort.class);
        LsmCompactionJobEntity job = compactionJob();
        org.mockito.Mockito.when(jobs.findBoundaryJob(EXECUTION_ID, 1L, 2L))
                .thenReturn(Optional.of(job));
        org.mockito.Mockito.when(
                        jobs.transition(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any(Boolean.class)))
                .thenAnswer(
                        invocation -> {
                            job.setStatus(invocation.getArgument(3));
                            return true;
                        });
        IdGenerator idGenerator = prefix -> prefix + "-" + generatedIds.incrementAndGet();
        LsmCompactionRecoveryService recovery =
                new LsmCompactionRecoveryService(
                        generator, segments, jobs, executions, new LsmDocumentCodec(), idGenerator);

        // Execute the two-level Task 7 candidate chain through the real PostgreSQL repository.
        TenantContext.run(TENANT_A, () -> recovery.resume(cascadedCompaction()));

        // The parent must reference the child's durable ID, never its planner placeholder.
        String childId =
                jdbc.queryForObject(
                        FIND_LEVEL_SEGMENT_ID_SQL, String.class, TENANT_A, SESSION_ID, 0);
        String parentSourceId =
                jdbc.queryForObject(
                        FIND_LEVEL_SOURCE_ID_SQL, String.class, TENANT_A, SESSION_ID, 1);
        assertThat(parentSourceId).isEqualTo(childId).doesNotStartWith("planned-");
        assertThat(
                        jdbc.queryForObject(
                                FIND_LEVEL_SOURCE_COUNT_SQL,
                                Integer.class,
                                TENANT_A,
                                SESSION_ID,
                                1))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(COUNT_ALL_SEGMENTS_SQL, Integer.class, TENANT_A, SESSION_ID))
                .isEqualTo(2);
    }

    @Test
    void databaseAllowsOnlyOneManifestForTenantSessionAndRejectsStaleCas() {
        insertManifest("manifest_current", 7L, 3L);

        assertThatThrownBy(() -> insertManifest("manifest_duplicate", 0L, 0L))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);

        LsmContextManifestEntity replacement = manifest(4L);
        boolean replaced =
                TenantContext.call(
                        TENANT_A,
                        () -> manifests.compareAndSetManifest(SESSION_ID, 6L, 3L, replacement));

        assertThat(replaced).isFalse();
        assertThat(TenantContext.call(TENANT_A, () -> manifests.findManifest(SESSION_ID)))
                .hasValueSatisfying(
                        manifest -> {
                            assertThat(manifest.getVersion()).isEqualTo(7L);
                            assertThat(manifest.getGeneration()).isEqualTo(3L);
                        });
        assertThat(TenantContext.call(TENANT_B, () -> manifests.findManifest(SESSION_ID)))
                .isEmpty();
    }

    private void insertManifest(String manifestId, long version, long generation) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                INSERT_MANIFEST_SQL,
                manifestId,
                TENANT_A,
                SESSION_ID,
                EMPTY_ARRAY_JSON,
                generation,
                now,
                SYSTEM_OPERATOR,
                now,
                SYSTEM_OPERATOR,
                version);
    }

    private static LsmSummarySegmentEntity segment(String segmentId) {
        LocalDateTime now = LocalDateTime.now();
        LsmSummarySegmentEntity segment = new LsmSummarySegmentEntity();
        segment.setSummarySegmentId(segmentId);
        segment.setConversationSessionId(SESSION_ID);
        segment.setSegmentKey(SEGMENT_KEY);
        segment.setKind(STANDARD_KIND);
        segment.setLevel(0);
        segment.setStartTurnSequence(1L);
        segment.setEndTurnSequence(1L);
        segment.setStartMessageSequence(1L);
        segment.setEndMessageSequence(1L);
        segment.setPartialSegment(false);
        segment.setSummaryDocument(EMPTY_OBJECT_JSON);
        segment.setEstimatedTokenCount(0);
        segment.setSourceSegmentIds(EMPTY_ARRAY_JSON);
        segment.setSourceContentSha256(CONTENT_HASH);
        segment.setSummarySchemaVersion(1);
        segment.setPromptVersion(1);
        segment.setModelProvider(SYSTEM_OPERATOR);
        segment.setModelName(SYSTEM_OPERATOR);
        segment.setCreationPolicy(EMPTY_OBJECT_JSON);
        segment.setSourceSegmentCount(0);
        segment.setCreatedAt(now);
        segment.setCreatedBy(SYSTEM_OPERATOR);
        segment.setUpdatedAt(now);
        segment.setUpdatedBy(SYSTEM_OPERATOR);
        segment.setDeleted(false);
        segment.setVersion(0L);
        return segment;
    }

    private static LsmSummarySegmentEntity generatedSegment(
            String segmentId, GeneratedSummary generated) {
        LsmSummarySegmentEntity segment = segment(segmentId);
        segment.setSegmentKey(generated.segmentKey());
        segment.setSummaryDocument(generated.serializedJson());
        segment.setEstimatedTokenCount(generated.estimatedTokenCount());
        segment.setSourceSegmentIds("[\"%s\"]".formatted(SOURCE_ID));
        segment.setModelProvider(generated.provider());
        segment.setModelName(generated.model());
        segment.setSourceSegmentCount(1);
        return segment;
    }

    private static ContextSummaryGenerator integrationGenerator() {
        return new ContextSummaryGenerator(
                request -> new ContextSummaryResult(VALID_SUMMARY, 100, MODEL_PROVIDER, MODEL_NAME),
                new ContextSummaryDocumentValidator(),
                new ConversationTokenEstimator(),
                new LsmSegmentKeyFactory());
    }

    private static PlannedCompaction cascadedCompaction() {
        ContextExecutionRef execution =
                new ContextExecutionRef(TENANT_A, SESSION_ID, EXECUTION_ID, OWNER_ID, 1L, 2L);
        ContextPolicySnapshot policy = new ContextPolicySnapshot(1, 3, 2, 10, 75, 65, 100, 109, 10);
        CompactionRequest request = org.mockito.Mockito.mock(CompactionRequest.class);
        org.mockito.Mockito.when(request.execution()).thenReturn(execution);
        org.mockito.Mockito.when(request.policySnapshot()).thenReturn(policy);
        org.mockito.Mockito.when(request.planningInput())
                .thenReturn(org.mockito.Mockito.mock(LsmPlanningInput.class));
        org.mockito.Mockito.when(request.candidateInputs())
                .thenReturn(List.of(childInput(), parentInput()));
        return new PlannedCompaction(
                JOB_ID, request, cascadedPlan(), 7L, 3L, JobStatus.PLANNED, false);
    }

    private static LsmCompactionPlan cascadedPlan() {
        LsmCompactionPlan.PlannedOperation child =
                new LsmCompactionPlan.PlannedOperation(
                        LsmCompactionPlan.OperationType.SEAL_L0,
                        PLANNED_CHILD_ID,
                        List.of(),
                        LsmSummarySegment.SegmentKind.STANDARD,
                        0,
                        new LsmSummarySegment.Coverage(1, 1),
                        false,
                        100);
        LsmCompactionPlan.PlannedOperation parent =
                new LsmCompactionPlan.PlannedOperation(
                        LsmCompactionPlan.OperationType.PROMOTE,
                        PLANNED_PARENT_ID,
                        List.of(PLANNED_CHILD_ID),
                        LsmSummarySegment.SegmentKind.STANDARD,
                        1,
                        new LsmSummarySegment.Coverage(1, 1),
                        false,
                        100);
        LsmSummarySegment projectedParent =
                new LsmSummarySegment(
                        PLANNED_PARENT_ID,
                        LsmSummarySegment.SegmentKind.STANDARD,
                        1,
                        new LsmSummarySegment.Coverage(1, 1),
                        100,
                        List.of(PLANNED_CHILD_ID),
                        false);
        LsmContextManifest manifest =
                new LsmContextManifest(
                        PLANNED_PARENT_ID,
                        List.of(PLANNED_PARENT_ID),
                        null,
                        null,
                        null,
                        null,
                        8L,
                        3,
                        2);
        return new LsmCompactionPlan(
                new LsmCompactionPlan.HotBoundary(null, null, 0, null),
                List.of(child, parent),
                manifest,
                List.of(projectedParent));
    }

    private static CandidateInput childInput() {
        return new CandidateInput(
                PLANNED_CHILD_ID,
                new GenerationRequest(
                        LsmSummarySegment.SegmentKind.STANDARD,
                        0,
                        new LsmSummarySegment.Coverage(1, 1),
                        new MessageCoverage(1, 2),
                        List.of(SOURCE_ID),
                        SOURCE_DOCUMENT));
    }

    private static CandidateInput parentInput() {
        return new CandidateInput(
                PLANNED_PARENT_ID,
                new GenerationRequest(
                        LsmSummarySegment.SegmentKind.STANDARD,
                        1,
                        new LsmSummarySegment.Coverage(1, 1),
                        new MessageCoverage(1, 2),
                        List.of(PLANNED_CHILD_ID),
                        VALID_SUMMARY));
    }

    private static LsmCompactionJobEntity compactionJob() {
        LsmCompactionJobEntity job = new LsmCompactionJobEntity();
        job.setCompactionJobId(JOB_ID);
        job.setExecutionId(EXECUTION_ID);
        job.setExecutionAttempt(1L);
        job.setInputUpToMessageSequence(2L);
        job.setOwnerInstanceId(OWNER_ID);
        job.setStatus(JobStatus.PLANNED.name());
        job.setVersion(0L);
        return job;
    }

    private static LsmContextManifestEntity manifest(long generation) {
        LocalDateTime now = LocalDateTime.now();
        LsmContextManifestEntity manifest = new LsmContextManifestEntity();
        manifest.setActiveSegmentIds(EMPTY_ARRAY_JSON);
        manifest.setWarmCutoffTurnSequence(0L);
        manifest.setHotFromTurnSequence(0L);
        manifest.setEvaluatedUpToMessageSequence(0L);
        manifest.setEffectivePolicyVersion(1);
        manifest.setEffectiveFanout(3);
        manifest.setEffectiveMaxLevel(1);
        manifest.setGeneration(generation);
        manifest.setUpdatedAt(now);
        manifest.setUpdatedBy(SYSTEM_OPERATOR);
        return manifest;
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
        LsmMapperConfiguration.class,
        LsmSummarySegmentRepository.class,
        LsmContextManifestRepository.class,
        LsmExecutionPolicyRepository.class,
        LsmDocumentCodec.class,
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
