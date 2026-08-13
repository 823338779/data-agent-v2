package com.datagent.agentv2.context.lsm.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

import com.datagent.agentv2.context.lsm.configuration.LsmMapperConfiguration;
import com.datagent.agentv2.context.lsm.domain.LsmCompactionPlan;
import com.datagent.agentv2.context.lsm.domain.LsmContextManifest;
import com.datagent.agentv2.context.lsm.domain.LsmPlanningInput;
import com.datagent.agentv2.context.lsm.domain.LsmSummarySegment;
import com.datagent.agentv2.context.lsm.persistence.json.LsmDocumentCodec;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmCompactionJobRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmContextManifestRepository;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmSummarySegmentRepository;
import com.datagent.agentv2.context.lsm.planning.LsmInvariantValidator;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.CompactionRequest;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.JobStatus;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.PlannedCompaction;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.PublishResult;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator.ReadyCompaction;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.context.lsm.domain.LsmContextPolicySnapshot;
import com.datagent.agentv2.persistence.MybatisPlusConfiguration;
import com.datagent.agentv2.platform.context.TenantContext;
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

/** 使用真实 PostgreSQL 验证 job fencing 与 manifest CAS 的原子发布。 */
@SpringBootTest(
        classes = LsmManifestPublicationIntegrationTest.Application.class,
        properties = "spring.flyway.enabled=false")
class LsmManifestPublicationIntegrationTest {

    private static final String TENANT_ID = "tenant_publication";
    private static final String USER_ID = "user_publication";
    private static final String SESSION_ID = "session_publication";
    private static final String EXECUTION_ID = "execution_publication";
    private static final String OWNER_ID = "owner_current";
    private static final String STALE_OWNER_ID = "owner_stale";
    private static final String JOB_ID = "job_publication";
    private static final String SEGMENT_ID = "segment_publication";
    private static final String EMPTY_JSON = "{}";
    private static final String EMPTY_ARRAY = "[]";
    private static final String ZERO_HASH =
            "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String PRIMARY_HISTORY = "flyway_schema_history";
    private static final String LSM_HISTORY = "flyway_agent_context_lsm_schema_history";
    private static final String INITIAL_BASELINE_VERSION = "0";
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    static {
        POSTGRES.start();
        flyway("classpath:db/migration", PRIMARY_HISTORY).migrate();
        flyway("classpath:db/agent-context-lsm/migration", LSM_HISTORY).migrate();
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LsmManifestPublicationTransaction publication;
    @Autowired private LsmInvariantValidator invariants;
    @Autowired private ContextExecutionControlPort executions;

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
    void seed() {
        reset(invariants);
        reset(executions);
        jdbc.execute("truncate table tenant cascade");
        jdbc.update("insert into tenant (tenant_id, name) values (?, ?)", TENANT_ID, TENANT_ID);
        jdbc.update(
                "insert into user_account (user_id, tenant_id, username, password_hash) values (?, ?, ?, ?)",
                USER_ID,
                TENANT_ID,
                USER_ID,
                "unused");
        jdbc.update(
                "insert into conversation_session (conversation_session_id, tenant_id, title, session_type, created_by_user_id) values (?, ?, ?, ?, ?)",
                SESSION_ID,
                TENANT_ID,
                "Publication",
                "chat",
                USER_ID);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "insert into conversation_context_lsm_manifest (context_manifest_id, tenant_id, conversation_session_id, active_segment_ids, warm_cutoff_turn_sequence, hot_from_turn_sequence, evaluated_up_to_message_sequence, effective_policy_version, effective_fanout, effective_max_level, generation, created_at, created_by, updated_at, updated_by, deleted, version) values (?, ?, ?, ?::jsonb, 0, 0, 0, 1, 3, 2, 7, ?, ?, ?, ?, false, 3)",
                "manifest_publication",
                TENANT_ID,
                SESSION_ID,
                EMPTY_ARRAY,
                now,
                "test",
                now,
                "test");
        jdbc.update(
                "insert into conversation_context_lsm_segment (summary_segment_id, tenant_id, conversation_session_id, segment_key, kind, level, start_turn_sequence, end_turn_sequence, start_message_sequence, end_message_sequence, partial_segment, summary_document, estimated_token_count, source_segment_ids, source_content_sha256, summary_schema_version, prompt_version, model_provider, model_name, creation_policy, source_segment_count, created_at, created_by, updated_at, updated_by, deleted, version) values (?, ?, ?, ?, 'STANDARD', 0, 1, 1, 1, 2, false, ?::jsonb, 10, ?::jsonb, ?, 1, 1, 'test', 'test', ?::jsonb, 1, ?, 'test', ?, 'test', false, 0)",
                SEGMENT_ID,
                TENANT_ID,
                SESSION_ID,
                ZERO_HASH,
                EMPTY_JSON,
                "[\"raw-1\"]",
                ZERO_HASH,
                EMPTY_JSON,
                now,
                now);
    }

    @Test
    void staleOwnerCannotPublishReadyCandidates() {
        insertReadyJob(OWNER_ID);
        ReadyCompaction ready = ready(STALE_OWNER_ID);

        PublishResult result = TenantContext.call(TENANT_ID, () -> publication.publish(ready));

        assertThat(result).isEqualTo(PublishResult.SUPERSEDED);
        assertThat(generation()).isEqualTo(7L);
        assertThat(jobStatus()).isEqualTo(JobStatus.READY.name());
    }

    @Test
    void staleSourceManifestIsSupersededWithoutReplacingTheNewManifest() {
        insertReadyJob(OWNER_ID);
        jdbc.update(
                "update conversation_context_lsm_manifest set generation = 8, version = 4 where tenant_id = ? and conversation_session_id = ?",
                TENANT_ID,
                SESSION_ID);

        PublishResult result =
                TenantContext.call(TENANT_ID, () -> publication.publish(ready(OWNER_ID)));

        assertThat(result).isEqualTo(PublishResult.SUPERSEDED);
        assertThat(generation()).isEqualTo(8L);
        assertThat(jobStatus()).isEqualTo(JobStatus.SUPERSEDED.name());
    }

    @Test
    void failureBeforeCasKeepsOldManifestActive() {
        insertReadyJob(OWNER_ID);
        doThrow(new IllegalStateException("injected before publish"))
                .when(invariants)
                .validatePlan(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(
                        () ->
                                TenantContext.call(
                                        TENANT_ID, () -> publication.publish(ready(OWNER_ID))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(generation()).isEqualTo(7L);
        assertThat(jobStatus()).isEqualTo(JobStatus.READY.name());
    }

    @Test
    void successfulPublishAndPostCommitRecoveryAreIdempotent() {
        insertReadyJob(OWNER_ID);
        ReadyCompaction ready = ready(OWNER_ID);

        assertThat(TenantContext.call(TENANT_ID, () -> publication.publish(ready)))
                .isEqualTo(PublishResult.PUBLISHED);
        assertThat(TenantContext.call(TENANT_ID, () -> publication.publish(ready)))
                .isEqualTo(PublishResult.PUBLISHED);
        assertThat(generation()).isEqualTo(8L);
        assertThat(jobStatus()).isEqualTo(JobStatus.PUBLISHED.name());
    }

    @Test
    void failureAfterCasRollsBackManifestAndReadyJobTogether() {
        insertReadyJob(OWNER_ID);
        org.mockito.Mockito.doThrow(new IllegalStateException("injected after cas"))
                .when(executions)
                .publish(any(), any());

        assertThatThrownBy(
                        () ->
                                TenantContext.call(
                                        TENANT_ID, () -> publication.publish(ready(OWNER_ID))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(generation()).isEqualTo(7L);
        assertThat(jobStatus()).isEqualTo(JobStatus.READY.name());
    }

    private void insertReadyJob(String owner) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "insert into conversation_context_lsm_job (compaction_job_id, tenant_id, conversation_session_id, execution_id, execution_attempt, input_up_to_message_sequence, source_manifest_generation, source_manifest_version, policy_snapshot, plan_document, plan_sha256, status, owner_instance_id, created_at, created_by, updated_at, updated_by, deleted, version) values (?, ?, ?, ?, 1, 2, 7, 3, ?::jsonb, ?::jsonb, ?, 'READY', ?, ?, 'test', ?, 'test', false, 0)",
                JOB_ID,
                TENANT_ID,
                SESSION_ID,
                EXECUTION_ID,
                EMPTY_JSON,
                EMPTY_JSON,
                ZERO_HASH,
                owner,
                now,
                now);
    }

    private ReadyCompaction ready(String owner) {
        ContextExecutionRef execution =
                new ContextExecutionRef(TENANT_ID, SESSION_ID, EXECUTION_ID, owner, 1L, 2L);
        LsmContextPolicySnapshot policy = new LsmContextPolicySnapshot(1, 3, 2, 10, 75, 65, 100, 109, 10);
        CompactionRequest request = mock(CompactionRequest.class);
        LsmPlanningInput planningInput = mock(LsmPlanningInput.class);
        org.mockito.Mockito.when(request.execution()).thenReturn(execution);
        org.mockito.Mockito.when(request.policySnapshot()).thenReturn(policy);
        org.mockito.Mockito.when(request.planningInput()).thenReturn(planningInput);
        LsmSummarySegment segment =
                new LsmSummarySegment(
                        SEGMENT_ID,
                        LsmSummarySegment.SegmentKind.STANDARD,
                        0,
                        new LsmSummarySegment.Coverage(1, 1),
                        10,
                        List.of("raw-1"),
                        false);
        LsmContextManifest manifest =
                new LsmContextManifest(null, List.of(SEGMENT_ID), null, null, null, null, 8, 3, 2);
        LsmCompactionPlan plan =
                new LsmCompactionPlan(
                        new LsmCompactionPlan.HotBoundary(null, null, 0, null),
                        List.of(),
                        manifest,
                        List.of(segment));
        PlannedCompaction planned =
                new PlannedCompaction(JOB_ID, request, plan, 7L, 3L, JobStatus.READY, false);
        return new ReadyCompaction(planned, plan, List.of(segment), false);
    }

    private long generation() {
        return jdbc.queryForObject(
                "select generation from conversation_context_lsm_manifest where tenant_id = ? and conversation_session_id = ?",
                Long.class,
                TENANT_ID,
                SESSION_ID);
    }

    private String jobStatus() {
        return jdbc.queryForObject(
                "select status from conversation_context_lsm_job where tenant_id = ? and compaction_job_id = ?",
                String.class,
                TENANT_ID,
                JOB_ID);
    }

    private static Flyway flyway(String location, String history) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(location)
                .table(history)
                .baselineOnMigrate(true)
                .baselineVersion(INITIAL_BASELINE_VERSION)
                .load();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        MybatisPlusConfiguration.class,
        LsmMapperConfiguration.class,
        LsmCompactionJobRepository.class,
        LsmContextManifestRepository.class,
        LsmSummarySegmentRepository.class,
        LsmDocumentCodec.class,
        RuntimeConfiguration.class
    })
    static class Application {}

    @TestConfiguration(proxyBeanMethods = false)
    static class RuntimeConfiguration {

        @Bean
        ContextExecutionControlPort executionControl() {
            return mock(ContextExecutionControlPort.class);
        }

        @Bean
        LsmInvariantValidator invariantValidator() {
            return mock(LsmInvariantValidator.class);
        }

        @Bean
        LsmManifestPublicationTransaction publication(
                LsmContextManifestRepository manifests,
                LsmSummarySegmentRepository segments,
                LsmCompactionJobRepository jobs,
                ContextExecutionControlPort executions,
                LsmInvariantValidator invariants,
                LsmDocumentCodec documents) {
            return new LsmManifestPublicationTransaction(
                    manifests, segments, jobs, executions, invariants, documents);
        }
    }
}
