package com.datagent.agentv2.context.hierarchical.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.hierarchical.configuration.HierarchicalMapperConfiguration;
import com.datagent.agentv2.context.hierarchical.domain.FrontierSelection;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalColdCandidate;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionJob;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionPlan;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionPlan.TurnRange;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.persistence.json.HierarchicalDocumentCodec;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCheckpointRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCompactionJobRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalSummaryNodeRepository;
import com.datagent.agentv2.context.hierarchical.planning.HierarchicalInvariantValidator;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCandidateGenerationService.ReadyCompaction;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCheckpointPublicationTransaction.PublishResult;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction.GenerationSettings;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction.PlannedCompaction;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionRecoveryService.RecoveryAction;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionRecoveryService.RecoveryResult;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.persistence.MybatisPlusConfiguration;
import com.datagent.agentv2.platform.context.TenantContext;
import java.util.List;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

/** 使用真实 PostgreSQL 验证 durable job 接管和每个恢复边界的 fencing。 */
@SpringBootTest(
        classes = HierarchicalCompactionRecoveryTest.Application.class,
        properties = "spring.flyway.enabled=false")
class HierarchicalCompactionRecoveryTest {

    private static final String TENANT_ID = "tenant-recovery";
    private static final String USER_ID = "user-recovery";
    private static final String SESSION_ID = "session-recovery";
    private static final String EXECUTION_ID = "execution-recovery";
    private static final String OLD_OWNER_ID = "owner-old";
    private static final String NEW_OWNER_ID = "owner-new";
    private static final String JOB_ID = "job-recovery";
    private static final String CONFLICT_JOB_ID = "job-conflict";
    private static final String CHECKPOINT_ID = "checkpoint-recovery";
    private static final String PRIMARY_HISTORY = "flyway_schema_history";
    private static final String HIERARCHICAL_HISTORY =
            "flyway_agent_context_hierarchical_schema_history";
    private static final String INITIAL_BASELINE_VERSION = "0";
    private static final String EMPTY_FRONTIER = "{\"schemaVersion\":1,\"nodes\":[]}";
    private static final String PLAN_DOCUMENT = "{\"schemaVersion\":3,\"warmTokenBudget\":600}";
    private static final String PROVIDER = "provider-recovery";
    private static final String MODEL = "model-recovery";
    private static final HierarchicalContextPolicySnapshot FROZEN_POLICY =
            new HierarchicalContextPolicySnapshot(1, 0.75d, 0.70d, 10, 100, 1, 1);
    private static final GenerationSettings FROZEN_SETTINGS =
            new GenerationSettings(FROZEN_POLICY, 512, 1, 1, PROVIDER, MODEL);
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    static {
        POSTGRES.start();
        flyway("classpath:db/migration", PRIMARY_HISTORY).migrate();
        flyway("classpath:db/agent-context-hierarchical/migration", HIERARCHICAL_HISTORY).migrate();
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private HierarchicalCheckpointRepository checkpoints;
    @Autowired private HierarchicalCompactionJobRepository jobs;
    @Autowired private HierarchicalCompactionPlanningTransaction planning;
    @Autowired private HierarchicalCandidateGenerationService generation;
    @Autowired private HierarchicalCompactionRecoveryService recovery;
    @Autowired private HierarchicalCompactionClaimTransaction claims;
    @Autowired private HierarchicalCheckpointPublicationTransaction publication;
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
    void seedSession() {
        reset(planning, generation, executions);
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
                "Recovery",
                "chat",
                USER_ID);
        TenantContext.run(TENANT_ID, () -> checkpoints.create(checkpoint(0L, 5L, 0L)));
    }

    @ParameterizedTest
    @EnumSource(
            value = HierarchicalCompactionJob.Status.class,
            names = {"PLANNED", "GENERATING", "READY_TO_PUBLISH"})
    void takeoverResumesPlannedGeneratingAndReadyWithFrozenBoundary(
            HierarchicalCompactionJob.Status status) {
        TenantContext.run(TENANT_ID, () -> jobs.insertOrSelect(job(JOB_ID, status, 1L)));
        stubResume(status);

        RecoveryResult result =
                TenantContext.call(
                        TENANT_ID, () -> recovery.recover(newExecution(), currentCheckpoint()));

        assertThat(result.action()).isEqualTo(RecoveryAction.PUBLISH);
        HierarchicalCompactionJob claimed =
                TenantContext.call(TENANT_ID, () -> jobs.findJob(JOB_ID));
        assertThat(claimed.ownerInstanceId()).isEqualTo(NEW_OWNER_ID);
        assertThat(claimed.executionAttempt()).isEqualTo(2L);
        assertThat(claimed.inputUpToMessageSequence()).isEqualTo(50L);
        verify(planning).restore(claimed, newExecution());
        verify(generation).generate(any(PlannedCompaction.class));

        // claim 更新 owner/attempt 后，旧 owner 的状态和 Cold 写入都被真实 SQL fence 拒绝。
        assertThat(
                        TenantContext.call(
                                TENANT_ID,
                                () ->
                                        jobs.transitionJob(
                                                JOB_ID,
                                                claimed.status(),
                                                HierarchicalCompactionJob.Status.FAILED,
                                                oldExecution(),
                                                "old-owner")))
                .isFalse();
        assertThat(
                        TenantContext.call(
                                TENANT_ID,
                                () ->
                                        jobs.storeColdCandidate(
                                                JOB_ID, oldExecution(), coldCandidate())))
                .isFalse();
    }

    @Test
    void currentAttemptCancellationIsObservedWithoutReplanning() {
        TenantContext.run(
                TENANT_ID,
                () ->
                        jobs.insertOrSelect(
                                new HierarchicalCompactionJob(
                                        JOB_ID,
                                        SESSION_ID,
                                        EXECUTION_ID,
                                        2L,
                                        NEW_OWNER_ID,
                                        50L,
                                        0L,
                                        5L,
                                        PLAN_DOCUMENT,
                                        HierarchicalCompactionJob.Status.CANCELLED,
                                        null,
                                        0L)));

        RecoveryResult result =
                TenantContext.call(
                        TENANT_ID, () -> recovery.recover(newExecution(), currentCheckpoint()));

        assertThat(result.action()).isEqualTo(RecoveryAction.CANCELLED);
        verifyNoInteractions(planning, generation);
    }

    @Test
    void committedPublicationIsObservedIdempotentlyAfterCallerCrash() {
        TenantContext.run(
                TENANT_ID,
                () ->
                        jobs.insertOrSelect(
                                job(JOB_ID, HierarchicalCompactionJob.Status.SUCCEEDED, 1L)));
        jdbc.update(
                "update conversation_context_hierarchical_checkpoint set strategy_generation = 6, version = 1 where tenant_id = ? and conversation_session_id = ?",
                TENANT_ID,
                SESSION_ID);
        HierarchicalCheckpoint published = currentCheckpoint();

        RecoveryResult first =
                TenantContext.call(TENANT_ID, () -> recovery.recover(newExecution(), published));
        RecoveryResult repeated =
                TenantContext.call(TENANT_ID, () -> recovery.recover(newExecution(), published));

        assertThat(first.action()).isEqualTo(RecoveryAction.COMPLETED);
        assertThat(first.publishedCheckpoint()).isEqualTo(published);
        assertThat(repeated).isEqualTo(first);
        verifyNoInteractions(planning, generation);
    }

    @Test
    void claimRejectsAnotherJobAlreadyCreatedForCurrentAttempt() {
        TenantContext.run(
                TENANT_ID,
                () -> {
                    jobs.insertOrSelect(
                            job(JOB_ID, HierarchicalCompactionJob.Status.GENERATING, 1L));
                    jobs.insertOrSelect(
                            job(CONFLICT_JOB_ID, HierarchicalCompactionJob.Status.PLANNED, 2L));
                });

        assertThatThrownBy(
                        () ->
                                TenantContext.call(
                                        TENANT_ID,
                                        () ->
                                                recovery.recover(
                                                        newExecution(), currentCheckpoint())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Hierarchical current-attempt compaction job conflicts with takeover");
    }

    @Test
    void recoveryDoesNotClaimJobFromAnotherCheckpointGeneration() {
        TenantContext.run(
                TENANT_ID,
                () ->
                        jobs.insertOrSelect(
                                new HierarchicalCompactionJob(
                                        JOB_ID,
                                        SESSION_ID,
                                        EXECUTION_ID,
                                        1L,
                                        OLD_OWNER_ID,
                                        50L,
                                        9L,
                                        9L,
                                        PLAN_DOCUMENT,
                                        HierarchicalCompactionJob.Status.GENERATING,
                                        null,
                                        0L)));

        RecoveryResult result =
                TenantContext.call(
                        TENANT_ID, () -> recovery.recover(newExecution(), currentCheckpoint()));

        assertThat(result.action()).isEqualTo(RecoveryAction.NONE);
        assertThat(TenantContext.call(TENANT_ID, () -> jobs.findJob(JOB_ID)).ownerInstanceId())
                .isEqualTo(OLD_OWNER_ID);
        verifyNoInteractions(planning, generation);
    }

    @Test
    void staleReadyJobResetsAtomicallyOnTheSameNaturalKeyThenResumesAndPublishes() {
        TenantContext.run(
                TENANT_ID,
                () -> {
                    jobs.insertOrSelect(
                            new HierarchicalCompactionJob(
                                    JOB_ID,
                                    SESSION_ID,
                                    EXECUTION_ID,
                                    2L,
                                    NEW_OWNER_ID,
                                    50L,
                                    0L,
                                    5L,
                                    PLAN_DOCUMENT,
                                    HierarchicalCompactionJob.Status.PLANNED,
                                    null,
                                    0L));
                    assertThat(
                                    jobs.transitionJob(
                                            JOB_ID,
                                            HierarchicalCompactionJob.Status.PLANNED,
                                            HierarchicalCompactionJob.Status.GENERATING,
                                            newExecution(),
                                            null))
                            .isTrue();
                    assertThat(jobs.storeColdCandidate(JOB_ID, newExecution(), coldCandidate()))
                            .isTrue();
                    assertThat(
                                    jobs.transitionJob(
                                            JOB_ID,
                                            HierarchicalCompactionJob.Status.GENERATING,
                                            HierarchicalCompactionJob.Status.READY_TO_PUBLISH,
                                            newExecution(),
                                            null))
                            .isTrue();
                });
        jdbc.update(
                "update conversation_context_hierarchical_checkpoint set strategy_generation = 6, version = 1 where tenant_id = ? and conversation_session_id = ?",
                TENANT_ID,
                SESSION_ID);

        HierarchicalCompactionJob stale = TenantContext.call(TENANT_ID, () -> jobs.findJob(JOB_ID));
        HierarchicalCompactionJob replacement =
                new HierarchicalCompactionJob(
                        "ignored-new-job-id",
                        SESSION_ID,
                        EXECUTION_ID,
                        2L,
                        NEW_OWNER_ID,
                        50L,
                        1L,
                        6L,
                        PLAN_DOCUMENT,
                        HierarchicalCompactionJob.Status.PLANNED,
                        null,
                        0L);

        assertThat(
                        TenantContext.call(
                                TENANT_ID,
                                () -> jobs.resetStale(stale, replacement, newExecution())))
                .isTrue();
        HierarchicalCompactionJob resetJob =
                TenantContext.call(TENANT_ID, () -> jobs.findJob(JOB_ID));
        assertThat(resetJob.jobId()).isEqualTo(JOB_ID);
        assertThat(resetJob.status()).isEqualTo(HierarchicalCompactionJob.Status.PLANNED);
        assertThat(resetJob.baseCheckpointVersion()).isEqualTo(1L);
        assertThat(resetJob.baseStrategyGeneration()).isEqualTo(6L);
        assertThat(resetJob.planDocument()).contains("\"schemaVersion\": 3");
        assertThat(TenantContext.call(TENANT_ID, () -> jobs.findColdCandidate(JOB_ID))).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from conversation_context_hierarchical_compaction_job where tenant_id = ? and execution_id = ? and execution_attempt = ? and input_up_to_message_sequence = ?",
                                Long.class,
                                TENANT_ID,
                                EXECUTION_ID,
                                2L,
                                50L))
                .isEqualTo(1L);

        when(planning.restore(any(HierarchicalCompactionJob.class), any(ContextExecutionRef.class)))
                .thenAnswer(
                        invocation ->
                                planned(invocation.getArgument(0), invocation.getArgument(1)));
        when(generation.generate(any(PlannedCompaction.class)))
                .thenAnswer(
                        invocation -> {
                            PlannedCompaction planned = invocation.getArgument(0);
                            assertThat(
                                            TenantContext.call(
                                                    TENANT_ID,
                                                    () ->
                                                            jobs.transitionJob(
                                                                    JOB_ID,
                                                                    HierarchicalCompactionJob.Status
                                                                            .PLANNED,
                                                                    HierarchicalCompactionJob.Status
                                                                            .GENERATING,
                                                                    newExecution(),
                                                                    null)))
                                    .isTrue();
                            assertThat(
                                            TenantContext.call(
                                                    TENANT_ID,
                                                    () ->
                                                            jobs.transitionJob(
                                                                    JOB_ID,
                                                                    HierarchicalCompactionJob.Status
                                                                            .GENERATING,
                                                                    HierarchicalCompactionJob.Status
                                                                            .READY_TO_PUBLISH,
                                                                    newExecution(),
                                                                    null)))
                                    .isTrue();
                            return new ReadyCompaction(planned, false);
                        });

        RecoveryResult recovered =
                TenantContext.call(
                        TENANT_ID, () -> recovery.recover(newExecution(), currentCheckpoint()));
        assertThat(recovered.action()).isEqualTo(RecoveryAction.PUBLISH);
        assertThat(TenantContext.call(TENANT_ID, () -> publication.publish(recovered.ready())))
                .isEqualTo(PublishResult.PUBLISHED);
        HierarchicalCheckpoint published = currentCheckpoint();
        assertThat(published.version()).isEqualTo(2L);
        assertThat(published.strategyGeneration()).isEqualTo(7L);
        assertThat(TenantContext.call(TENANT_ID, () -> jobs.findJob(JOB_ID)).status())
                .isEqualTo(HierarchicalCompactionJob.Status.SUCCEEDED);
    }

    private void stubResume(HierarchicalCompactionJob.Status status) {
        when(planning.restore(any(HierarchicalCompactionJob.class), any(ContextExecutionRef.class)))
                .thenAnswer(
                        invocation ->
                                planned(invocation.getArgument(0), invocation.getArgument(1)));
        when(generation.generate(any(PlannedCompaction.class)))
                .thenAnswer(invocation -> new ReadyCompaction(invocation.getArgument(0), false));
    }

    private HierarchicalCheckpoint currentCheckpoint() {
        return TenantContext.call(TENANT_ID, () -> checkpoints.findCheckpoint(SESSION_ID));
    }

    private static PlannedCompaction planned(
            HierarchicalCompactionJob job, ContextExecutionRef execution) {
        return new PlannedCompaction(
                job, execution, FROZEN_SETTINGS, 600, emptyPlan(), List.of(), null);
    }

    private static HierarchicalCompactionPlan emptyPlan() {
        return new HierarchicalCompactionPlan(
                0,
                Optional.empty(),
                TurnRange.EMPTY,
                TurnRange.EMPTY,
                TurnRange.EMPTY,
                List.of(),
                List.of(),
                FrontierSelection.empty(),
                List.of());
    }

    private static HierarchicalCompactionJob job(
            String jobId, HierarchicalCompactionJob.Status status, long attempt) {
        return new HierarchicalCompactionJob(
                jobId,
                SESSION_ID,
                EXECUTION_ID,
                attempt,
                OLD_OWNER_ID,
                50L,
                0L,
                5L,
                PLAN_DOCUMENT,
                status,
                null,
                0L);
    }

    private static HierarchicalCheckpoint checkpoint(long pointer, long generation, long version) {
        return new HierarchicalCheckpoint(
                CHECKPOINT_ID,
                SESSION_ID,
                "{\"schemaVersion\":1}",
                pointer,
                0L,
                0,
                1,
                1,
                PROVIDER,
                MODEL,
                EMPTY_FRONTIER,
                generation,
                version);
    }

    private static HierarchicalColdCandidate coldCandidate() {
        return new HierarchicalColdCandidate(
                "cold-recovery", 1, 1, 1, 2, "{\"schemaVersion\":1}", 10, 1, 1, PROVIDER, MODEL);
    }

    private static ContextExecutionRef oldExecution() {
        return new ContextExecutionRef(TENANT_ID, SESSION_ID, EXECUTION_ID, OLD_OWNER_ID, 1L, 50L);
    }

    private static ContextExecutionRef newExecution() {
        return new ContextExecutionRef(TENANT_ID, SESSION_ID, EXECUTION_ID, NEW_OWNER_ID, 2L, 50L);
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
        HierarchicalCheckpointRepository.class,
        HierarchicalCompactionJobRepository.class,
        HierarchicalSummaryNodeRepository.class,
        HierarchicalDocumentCodec.class,
        RecoveryConfiguration.class
    })
    static class Application {}

    @TestConfiguration(proxyBeanMethods = false)
    static class RecoveryConfiguration {

        @Bean
        HierarchicalCompactionPlanningTransaction planning() {
            return mock(HierarchicalCompactionPlanningTransaction.class);
        }

        @Bean
        HierarchicalCandidateGenerationService generation() {
            return mock(HierarchicalCandidateGenerationService.class);
        }

        @Bean
        ContextExecutionControlPort executionControl() {
            return mock(ContextExecutionControlPort.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        HierarchicalCompactionClaimTransaction claims(
                HierarchicalCompactionJobRepository jobs, ContextExecutionControlPort executions) {
            return new HierarchicalCompactionClaimTransaction(jobs, executions);
        }

        @Bean
        HierarchicalCompactionRecoveryService recovery(
                HierarchicalCompactionJobRepository jobs,
                HierarchicalCheckpointRepository checkpoints,
                HierarchicalCompactionPlanningTransaction planning,
                HierarchicalCandidateGenerationService generation,
                HierarchicalCompactionClaimTransaction claims) {
            return new HierarchicalCompactionRecoveryService(
                    jobs, checkpoints, planning, generation, claims);
        }

        @Bean
        HierarchicalCheckpointPublicationTransaction publication(
                HierarchicalCheckpointRepository checkpoints,
                HierarchicalCompactionJobRepository jobs,
                HierarchicalSummaryNodeRepository nodes,
                HierarchicalCompactionPlanningTransaction planning,
                HierarchicalCandidateGenerationService generation,
                ContextExecutionControlPort executions,
                ObjectMapper json) {
            return new HierarchicalCheckpointPublicationTransaction(
                    checkpoints,
                    jobs,
                    nodes,
                    planning,
                    generation,
                    executions,
                    new HierarchicalInvariantValidator(),
                    json);
        }
    }
}
