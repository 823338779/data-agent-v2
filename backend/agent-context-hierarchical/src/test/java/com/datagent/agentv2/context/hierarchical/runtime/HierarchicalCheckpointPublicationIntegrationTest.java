package com.datagent.agentv2.context.hierarchical.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.datagent.agentv2.context.hierarchical.configuration.HierarchicalMapperConfiguration;
import com.datagent.agentv2.context.hierarchical.domain.FrontierSelection;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalColdCandidate;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionJob;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionPlan;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionPlan.TurnRange;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalSummaryNode;
import com.datagent.agentv2.context.hierarchical.domain.SummaryRange;
import com.datagent.agentv2.context.hierarchical.persistence.json.HierarchicalDocumentCodec;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCheckpointRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCompactionJobRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalSummaryNodeRepository;
import com.datagent.agentv2.context.hierarchical.planning.HierarchicalInvariantValidator;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCandidateGenerationService.ReadyCompaction;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCheckpointPublicationTransaction.PublishResult;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction.GenerationSettings;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction.PlannedCandidate;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionPlanningTransaction.PlannedCompaction;
import com.datagent.agentv2.context.hierarchical.summary.ContextSummaryDocumentValidator;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalNodeKeyFactory;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.GenerationRange;
import com.datagent.agentv2.context.hierarchical.summary.HierarchicalSummaryGenerator.SourceKind;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort;
import com.datagent.agentv2.conversation.context.ContextExecutionControlPort.ContextLifecycleEvent;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextSummaryModelPort;
import com.datagent.agentv2.persistence.MybatisPlusConfiguration;
import com.datagent.agentv2.platform.context.TenantContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import com.datagent.agentv2.platform.id.PrefixedUuidIdGenerator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

/** 使用真实 PostgreSQL 证明 checkpoint、job 和完成事件共享原子发布事务。 */
@SpringBootTest(
        classes = HierarchicalCheckpointPublicationIntegrationTest.Application.class,
        properties = "spring.flyway.enabled=false")
class HierarchicalCheckpointPublicationIntegrationTest {

    private static final String TENANT_ID = "tenant-publication";
    private static final String USER_ID = "user-publication";
    private static final String SESSION_ID = "session-publication";
    private static final String EXECUTION_ID = "execution-publication";
    private static final String OWNER_ID = "owner-current";
    private static final String STALE_OWNER_ID = "owner-stale";
    private static final String JOB_ID = "job-publication";
    private static final String CHECKPOINT_ID = "checkpoint-publication";
    private static final String PROMOTED_NODE_ID = "node-promoted";
    private static final String FRONTIER_NODE_ID = "node-frontier";
    private static final String MISSING_LEFT_NODE_ID = "node-missing-left";
    private static final String MISSING_RIGHT_NODE_ID = "node-missing-right";
    private static final String MISSING_PARENT_NODE_ID = "node-missing-parent";
    private static final String HOT_OLDEST_NODE_ID = "node-hot-oldest";
    private static final String HOT_NEWEST_NODE_ID = "node-hot-newest";
    private static final String COVERAGE_LEAF_1 = "node-coverage-leaf-1";
    private static final String COVERAGE_LEAF_2 = "node-coverage-leaf-2";
    private static final String COVERAGE_LEAF_3 = "node-coverage-leaf-3";
    private static final String COVERAGE_LEAF_4 = "node-coverage-leaf-4";
    private static final String COVERAGE_LEFT = "node-coverage-left";
    private static final String COVERAGE_RIGHT = "node-coverage-right";
    private static final String COVERAGE_ROOT = "node-coverage-root";
    private static final String COLD_CANDIDATE_ID = "cold-candidate";
    private static final String PROVIDER = "provider-publication";
    private static final String MODEL = "model-publication";
    private static final String PRIMARY_HISTORY = "flyway_schema_history";
    private static final String HIERARCHICAL_HISTORY =
            "flyway_agent_context_hierarchical_schema_history";
    private static final String INITIAL_BASELINE_VERSION = "0";
    private static final String EMPTY_FRONTIER = "{\"schemaVersion\":1,\"nodes\":[]}";
    private static final String SAFE_FAILURE_CODE = "CONTEXT_COMPACTION_FAILED";
    private static final int MAX_SUMMARY_TOKENS = 2_048;
    private static final int SUMMARY_ESTIMATED_TOKENS = 512;

    private static final HierarchicalContextPolicySnapshot POLICY =
            new HierarchicalContextPolicySnapshot(1, 0.75d, 0.70d, 1, 2, 1, 1);
    private static final GenerationSettings SETTINGS =
            new GenerationSettings(POLICY, MAX_SUMMARY_TOKENS, 1, 1, PROVIDER, MODEL);
    private static final SummaryRange PROMOTED_RANGE = new SummaryRange(1, 1, 1, 2, 0);
    private static final SummaryRange FRONTIER_RANGE = new SummaryRange(2, 2, 3, 4, 0);
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    static {
        POSTGRES.start();
        flyway("classpath:db/migration", PRIMARY_HISTORY).migrate();
        flyway("classpath:db/agent-context-hierarchical/migration", HIERARCHICAL_HISTORY).migrate();
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private HierarchicalCheckpointRepository checkpoints;
    @Autowired private HierarchicalSummaryNodeRepository nodes;
    @Autowired private HierarchicalCompactionJobRepository jobs;
    @Autowired private HierarchicalCheckpointPublicationTransaction publication;
    @Autowired private ContextExecutionControlPort executions;
    @Autowired private ObjectMapper json;

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
    }

    @Test
    void publishesColdPointerFrontierGenerationJobAndCompletedEventAtomically() {
        ReadyCompaction ready = TenantContext.call(TENANT_ID, this::seedReadyCompaction);

        PublishResult result = TenantContext.call(TENANT_ID, () -> publication.publish(ready));

        assertThat(result).isEqualTo(PublishResult.PUBLISHED);
        HierarchicalCheckpoint published =
                TenantContext.call(TENANT_ID, () -> checkpoints.findCheckpoint(SESSION_ID));
        assertThat(json.readTree(published.coldSummaryDocument()))
                .isEqualTo(json.readTree(coldCandidate().summaryDocument()));
        assertThat(published.summarizedUpToConversationMessageSequence()).isEqualTo(2L);
        assertThat(published.summarizedCompletedTurnCount()).isEqualTo(1L);
        assertThat(published.activeFrontierDocument()).contains(FRONTIER_NODE_ID);
        assertThat(published.strategyGeneration()).isEqualTo(6L);
        assertThat(published.version()).isEqualTo(1L);
        assertThat(TenantContext.call(TENANT_ID, () -> jobs.findJob(JOB_ID)).status())
                .isEqualTo(HierarchicalCompactionJob.Status.SUCCEEDED);
        verify(executions).publish(any(), any(ContextLifecycleEvent.class));
    }

    @Test
    void postCasFailureRollsBackCheckpointAndSucceededJobTogether() {
        ReadyCompaction ready = TenantContext.call(TENANT_ID, this::seedReadyCompaction);
        doThrow(new IllegalStateException("injected after checkpoint CAS"))
                .when(executions)
                .publish(any(), any());

        assertThatThrownBy(() -> TenantContext.call(TENANT_ID, () -> publication.publish(ready)))
                .isInstanceOf(IllegalStateException.class);

        HierarchicalCheckpoint unchanged =
                TenantContext.call(TENANT_ID, () -> checkpoints.findCheckpoint(SESSION_ID));
        assertThat(unchanged.strategyGeneration()).isEqualTo(5L);
        assertThat(unchanged.version()).isZero();
        assertThat(TenantContext.call(TENANT_ID, () -> jobs.findJob(JOB_ID)).status())
                .isEqualTo(HierarchicalCompactionJob.Status.READY_TO_PUBLISH);
    }

    @Test
    void staleCheckpointAndStaleOwnerCannotExposeCandidates() {
        ReadyCompaction ready = TenantContext.call(TENANT_ID, this::seedReadyCompaction);
        TenantContext.run(
                TENANT_ID,
                () -> {
                    HierarchicalCheckpoint current = checkpoints.findCheckpoint(SESSION_ID);
                    assertThat(
                                    checkpoints.compareAndSetCheckpoint(
                                            current,
                                            replacementForConcurrentPublisher(current),
                                            execution()))
                            .isTrue();
                });

        assertThat(TenantContext.call(TENANT_ID, () -> publication.publish(ready)))
                .isEqualTo(PublishResult.STALE);
        assertThat(
                        TenantContext.call(
                                TENANT_ID,
                                () -> publication.publish(withExecution(ready, staleExecution()))))
                .isEqualTo(PublishResult.STALE);
        assertThat(TenantContext.call(TENANT_ID, () -> jobs.findJob(JOB_ID)).status())
                .isEqualTo(HierarchicalCompactionJob.Status.READY_TO_PUBLISH);
        assertThat(
                        TenantContext.call(TENANT_ID, () -> checkpoints.findCheckpoint(SESSION_ID))
                                .activeFrontierDocument())
                .doesNotContain(FRONTIER_NODE_ID);
    }

    @Test
    void missingPublicationNodeFailsJobWithoutChangingCheckpoint() {
        ReadyCompaction ready = TenantContext.call(TENANT_ID, this::seedReadyCompaction);
        jdbc.update(
                "delete from conversation_context_hierarchical_summary_node where tenant_id = ? and hierarchical_summary_node_id = ?",
                TENANT_ID,
                FRONTIER_NODE_ID);

        assertThatThrownBy(() -> TenantContext.call(TENANT_ID, () -> publication.publish(ready)))
                .isInstanceOf(HierarchicalCompactionException.class)
                .hasMessage(SAFE_FAILURE_CODE);

        assertThat(
                        TenantContext.call(TENANT_ID, () -> checkpoints.findCheckpoint(SESSION_ID))
                                .strategyGeneration())
                .isEqualTo(5L);
        assertThat(TenantContext.call(TENANT_ID, () -> jobs.findJob(JOB_ID)))
                .extracting(
                        HierarchicalCompactionJob::status, HierarchicalCompactionJob::failureCode)
                .containsExactly(HierarchicalCompactionJob.Status.FAILED, SAFE_FAILURE_CODE);
    }

    @Test
    void gapOverlapAndParentChildPublicationPlansAreRejected() {
        ReadyCompaction valid = TenantContext.call(TENANT_ID, this::seedReadyCompaction);
        HierarchicalCompactionPlan gap =
                plan(
                        new FrontierSelection(
                                List.of(FRONTIER_NODE_ID),
                                List.of(new SummaryRange(3, 3, 5, 6, 0)),
                                List.of(),
                                10,
                                true,
                                true));
        HierarchicalCompactionPlan overlap =
                plan(
                        new FrontierSelection(
                                List.of(PROMOTED_NODE_ID, FRONTIER_NODE_ID),
                                List.of(PROMOTED_RANGE, FRONTIER_RANGE),
                                List.of(),
                                20,
                                true,
                                true));
        SummaryRange parentRange = new SummaryRange(1, 2, 1, 4, 1);
        HierarchicalCompactionPlan parentAndChild =
                plan(
                        new FrontierSelection(
                                List.of("node-parent", FRONTIER_NODE_ID),
                                List.of(parentRange, FRONTIER_RANGE),
                                List.of(),
                                20,
                                true,
                                true));

        for (HierarchicalCompactionPlan invalid : List.of(gap, overlap, parentAndChild)) {
            assertThatThrownBy(() -> publication.validatePublishedView(withPlan(valid, invalid)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void generatedMissingParentReplacesItsSelectedChildren() {
        SummaryRange left = new SummaryRange(3, 3, 5, 6, 0);
        SummaryRange right = new SummaryRange(4, 4, 7, 8, 0);
        SummaryRange parent = new SummaryRange(3, 4, 5, 8, 1);
        FrontierSelection frozen =
                new FrontierSelection(
                        List.of(MISSING_LEFT_NODE_ID, MISSING_RIGHT_NODE_ID),
                        List.of(left, right),
                        List.of(parent),
                        SUMMARY_ESTIMATED_TOKENS * 2,
                        false,
                        true);
        HierarchicalCompactionPlan plan =
                new HierarchicalCompactionPlan(
                        5,
                        Optional.empty(),
                        new TurnRange(1, 2),
                        new TurnRange(3, 4),
                        new TurnRange(5, 5),
                        List.of(),
                        List.of(parent),
                        frozen,
                        List.of());
        List<PlannedCandidate> candidates =
                List.of(
                        descriptor(MISSING_LEFT_NODE_ID, left, SourceKind.LEAF, null, null),
                        descriptor(MISSING_RIGHT_NODE_ID, right, SourceKind.LEAF, null, null),
                        descriptor(
                                MISSING_PARENT_NODE_ID,
                                parent,
                                SourceKind.PARENT,
                                MISSING_LEFT_NODE_ID,
                                MISSING_RIGHT_NODE_ID));

        FrontierSelection resolved =
                HierarchicalCheckpointPublicationTransaction.resolvedFrontier(
                        planned(plan, candidates),
                        Map.of(
                                MISSING_LEFT_NODE_ID,
                                node(MISSING_LEFT_NODE_ID, left),
                                MISSING_RIGHT_NODE_ID,
                                node(MISSING_RIGHT_NODE_ID, right),
                                MISSING_PARENT_NODE_ID,
                                node(
                                        MISSING_PARENT_NODE_ID,
                                        parent,
                                        MISSING_LEFT_NODE_ID,
                                        MISSING_RIGHT_NODE_ID)));

        assertThat(resolved.nodeIds()).containsExactly(MISSING_PARENT_NODE_ID);
        assertThat(resolved.ranges()).containsExactly(parent);
    }

    @Test
    void durableReselectionRestoresCompleteWarmCoverageAfterAnInitialGap() {
        SummaryRange leaf1 = new SummaryRange(1, 1, 1, 2, 0);
        SummaryRange leaf2 = new SummaryRange(2, 2, 3, 4, 0);
        SummaryRange leaf3 = new SummaryRange(3, 3, 5, 6, 0);
        SummaryRange leaf4 = new SummaryRange(4, 4, 7, 8, 0);
        SummaryRange left = new SummaryRange(1, 2, 1, 4, 1);
        SummaryRange right = new SummaryRange(3, 4, 5, 8, 1);
        SummaryRange root = new SummaryRange(1, 4, 1, 8, 2);
        FrontierSelection initialGap =
                new FrontierSelection(List.of(), List.of(), List.of(right), 0, false, true);
        HierarchicalCompactionPlan plan =
                new HierarchicalCompactionPlan(
                        4,
                        Optional.empty(),
                        TurnRange.EMPTY,
                        new TurnRange(1, 4),
                        TurnRange.EMPTY,
                        List.of(),
                        List.of(root),
                        initialGap,
                        List.of());
        List<PlannedCandidate> candidates =
                List.of(
                        descriptor(COVERAGE_LEAF_1, leaf1, SourceKind.LEAF, null, null),
                        descriptor(COVERAGE_LEAF_2, leaf2, SourceKind.LEAF, null, null),
                        descriptor(
                                COVERAGE_LEFT,
                                left,
                                SourceKind.PARENT,
                                COVERAGE_LEAF_1,
                                COVERAGE_LEAF_2),
                        descriptor(COVERAGE_LEAF_3, leaf3, SourceKind.LEAF, null, null),
                        descriptor(COVERAGE_LEAF_4, leaf4, SourceKind.LEAF, null, null),
                        descriptor(
                                COVERAGE_RIGHT,
                                right,
                                SourceKind.PARENT,
                                COVERAGE_LEAF_3,
                                COVERAGE_LEAF_4),
                        descriptor(
                                COVERAGE_ROOT,
                                root,
                                SourceKind.PARENT,
                                COVERAGE_LEFT,
                                COVERAGE_RIGHT));
        Map<String, HierarchicalSummaryNode> persisted =
                Map.of(
                        COVERAGE_LEAF_1, node(COVERAGE_LEAF_1, leaf1),
                        COVERAGE_LEAF_2, node(COVERAGE_LEAF_2, leaf2),
                        COVERAGE_LEFT, node(COVERAGE_LEFT, left, COVERAGE_LEAF_1, COVERAGE_LEAF_2),
                        COVERAGE_LEAF_3, node(COVERAGE_LEAF_3, leaf3),
                        COVERAGE_LEAF_4, node(COVERAGE_LEAF_4, leaf4),
                        COVERAGE_RIGHT,
                                node(COVERAGE_RIGHT, right, COVERAGE_LEAF_3, COVERAGE_LEAF_4),
                        COVERAGE_ROOT, node(COVERAGE_ROOT, root, COVERAGE_LEFT, COVERAGE_RIGHT));

        FrontierSelection resolved =
                HierarchicalCheckpointPublicationTransaction.resolvedFrontier(
                        planned(plan, candidates), persisted);

        assertThat(resolved.publishable()).isTrue();
        assertThat(resolved.nodeIds()).containsExactly(COVERAGE_ROOT);
        assertThat(resolved.ranges()).containsExactly(root);
    }

    @Test
    void publishesHotDegradationCandidatesAsAnOldestFirstPrefix() {
        SummaryRange oldest = new SummaryRange(1, 1, 1, 2, 0);
        SummaryRange newest = new SummaryRange(2, 2, 3, 4, 0);
        HierarchicalCompactionPlan plan =
                new HierarchicalCompactionPlan(
                        2,
                        Optional.of(3L),
                        TurnRange.EMPTY,
                        TurnRange.EMPTY,
                        new TurnRange(1, 2),
                        List.of(),
                        List.of(),
                        FrontierSelection.empty(),
                        List.of(oldest, newest));
        List<PlannedCandidate> candidates =
                List.of(
                        descriptor(HOT_OLDEST_NODE_ID, oldest, SourceKind.LEAF, null, null),
                        descriptor(HOT_NEWEST_NODE_ID, newest, SourceKind.LEAF, null, null));

        FrontierSelection resolved =
                HierarchicalCheckpointPublicationTransaction.resolvedFrontier(
                        planned(plan, candidates),
                        Map.of(
                                HOT_NEWEST_NODE_ID,
                                node(HOT_NEWEST_NODE_ID, newest),
                                HOT_OLDEST_NODE_ID,
                                node(HOT_OLDEST_NODE_ID, oldest)));

        assertThat(resolved.nodeIds()).containsExactly(HOT_OLDEST_NODE_ID, HOT_NEWEST_NODE_ID);
        assertThat(resolved.ranges()).containsExactly(oldest, newest);
    }

    private ReadyCompaction seedReadyCompaction() {
        HierarchicalCheckpoint checkpoint = checkpoints.create(initialCheckpoint());
        List<PlannedCandidate> candidates = candidates();
        HierarchicalCompactionPlan plan = plan(frontier());
        String planDocument = durablePlanDocument(checkpoint, plan, candidates, coldDescriptor());
        HierarchicalCompactionJob job = jobs.insertOrSelect(job(planDocument));
        assertThat(
                        jobs.transitionJob(
                                JOB_ID,
                                HierarchicalCompactionJob.Status.PLANNED,
                                HierarchicalCompactionJob.Status.GENERATING,
                                execution(),
                                null))
                .isTrue();

        // 候选节点和 Cold 正文先 durable，但只有 checkpoint 引用后才进入正常读视图。
        nodes.insertOrSelect(node(PROMOTED_NODE_ID, PROMOTED_RANGE));
        nodes.insertOrSelect(node(FRONTIER_NODE_ID, FRONTIER_RANGE));
        assertThat(jobs.storeColdCandidate(JOB_ID, execution(), coldCandidate())).isTrue();
        assertThat(
                        jobs.transitionJob(
                                JOB_ID,
                                HierarchicalCompactionJob.Status.GENERATING,
                                HierarchicalCompactionJob.Status.READY_TO_PUBLISH,
                                execution(),
                                null))
                .isTrue();
        HierarchicalCompactionJob readyJob = jobs.findJob(JOB_ID);
        PlannedCompaction planned =
                new PlannedCompaction(
                        readyJob, execution(), SETTINGS, 600, plan, candidates, coldDescriptor());
        return new ReadyCompaction(planned, false);
    }

    private String durablePlanDocument(
            HierarchicalCheckpoint checkpoint,
            HierarchicalCompactionPlan plan,
            List<PlannedCandidate> candidates,
            PlannedCandidate cold) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", 3);
        document.put("baseCheckpointVersion", checkpoint.version());
        document.put("baseStrategyGeneration", checkpoint.strategyGeneration());
        document.put("inputUpToMessageSequence", execution().inputUpToMessageSequence());
        document.put("targetInputTokens", 700);
        document.put("warmTokenBudget", 600);
        document.put("generationSettings", SETTINGS);
        document.put("plan", plan);
        document.put("candidates", candidates);
        document.put("coldCandidate", cold);
        return json.writeValueAsString(document);
    }

    private static HierarchicalCompactionPlan plan(FrontierSelection frontier) {
        return new HierarchicalCompactionPlan(
                3,
                Optional.empty(),
                new TurnRange(1, 1),
                new TurnRange(2, 2),
                new TurnRange(3, 3),
                List.of(PROMOTED_RANGE),
                List.of(FRONTIER_RANGE),
                frontier,
                List.of());
    }

    private static FrontierSelection frontier() {
        return new FrontierSelection(
                List.of(FRONTIER_NODE_ID),
                List.of(FRONTIER_RANGE),
                List.of(),
                SUMMARY_ESTIMATED_TOKENS,
                true,
                true);
    }

    private static List<PlannedCandidate> candidates() {
        return List.of(
                descriptor(PROMOTED_NODE_ID, PROMOTED_RANGE, SourceKind.LEAF, null, null),
                descriptor(FRONTIER_NODE_ID, FRONTIER_RANGE, SourceKind.LEAF, null, null));
    }

    private static PlannedCandidate coldDescriptor() {
        return new PlannedCandidate(
                COLD_CANDIDATE_ID,
                new GenerationRange(1, 1, 1, 2, 0),
                SourceKind.COLD,
                null,
                null,
                1,
                1,
                PROVIDER,
                MODEL);
    }

    private static PlannedCandidate descriptor(
            String id, SummaryRange range, SourceKind kind, String leftChild, String rightChild) {
        return new PlannedCandidate(
                id,
                new GenerationRange(
                        range.startTurn(),
                        range.endTurn(),
                        range.startMessageSequence(),
                        range.endMessageSequence(),
                        range.level()),
                kind,
                leftChild,
                rightChild,
                1,
                1,
                PROVIDER,
                MODEL);
    }

    private static HierarchicalCheckpoint initialCheckpoint() {
        return new HierarchicalCheckpoint(
                CHECKPOINT_ID,
                SESSION_ID,
                summaryDocument(1, 1, 1, 1),
                0L,
                0L,
                0,
                1,
                1,
                PROVIDER,
                MODEL,
                EMPTY_FRONTIER,
                5L,
                0L);
    }

    private static HierarchicalCheckpoint replacementForConcurrentPublisher(
            HierarchicalCheckpoint current) {
        return new HierarchicalCheckpoint(
                current.checkpointId(),
                current.conversationSessionId(),
                current.coldSummaryDocument(),
                current.summarizedUpToConversationMessageSequence(),
                current.summarizedCompletedTurnCount(),
                current.estimatedTokenCount(),
                current.summarySchemaVersion(),
                current.summaryPromptVersion(),
                current.modelProvider(),
                current.modelName(),
                current.activeFrontierDocument(),
                current.strategyGeneration() + 1L,
                current.version() + 1L);
    }

    private static HierarchicalCompactionJob job(String planDocument) {
        return new HierarchicalCompactionJob(
                JOB_ID,
                SESSION_ID,
                EXECUTION_ID,
                1L,
                OWNER_ID,
                6L,
                0L,
                5L,
                planDocument,
                HierarchicalCompactionJob.Status.PLANNED,
                null,
                0L);
    }

    private static HierarchicalSummaryNode node(String nodeId, SummaryRange range) {
        return node(nodeId, range, null, null);
    }

    private static HierarchicalSummaryNode node(
            String nodeId, SummaryRange range, String leftChild, String rightChild) {
        return new HierarchicalSummaryNode(
                nodeId,
                SESSION_ID,
                range.level(),
                range.coveredTurns(),
                range.startMessageSequence(),
                range.endMessageSequence(),
                leftChild,
                rightChild,
                summaryDocument(
                        range.startTurn(),
                        range.endTurn(),
                        range.startMessageSequence(),
                        range.endMessageSequence()),
                SUMMARY_ESTIMATED_TOKENS,
                1,
                1,
                PROVIDER,
                MODEL);
    }

    private static PlannedCompaction planned(
            HierarchicalCompactionPlan plan, List<PlannedCandidate> candidates) {
        return new PlannedCompaction(job("{}"), execution(), SETTINGS, 600, plan, candidates, null);
    }

    private static HierarchicalColdCandidate coldCandidate() {
        return new HierarchicalColdCandidate(
                COLD_CANDIDATE_ID,
                1,
                1,
                1,
                2,
                summaryDocument(1, 1, 1, 2),
                SUMMARY_ESTIMATED_TOKENS,
                1,
                1,
                PROVIDER,
                MODEL);
    }

    private static String summaryDocument(
            long startTurn, long endTurn, long startMessage, long endMessage) {
        return """
                {"schemaVersion":1,"coverage":{"startTurn":%d,"endTurn":%d,"startMessageSequence":%d,"endMessageSequence":%d},"confirmedFacts":[],"workingHypotheses":[],"decisions":[],"rejectedApproaches":[],"toolFindings":[],"nextSteps":[],"openQuestions":[]}
                """
                .formatted(startTurn, endTurn, startMessage, endMessage);
    }

    private static ContextExecutionRef execution() {
        return new ContextExecutionRef(TENANT_ID, SESSION_ID, EXECUTION_ID, OWNER_ID, 1L, 6L);
    }

    private static ContextExecutionRef staleExecution() {
        return new ContextExecutionRef(TENANT_ID, SESSION_ID, EXECUTION_ID, STALE_OWNER_ID, 1L, 6L);
    }

    private static ReadyCompaction withExecution(
            ReadyCompaction ready, ContextExecutionRef execution) {
        PlannedCompaction source = ready.planned();
        PlannedCompaction replaced =
                new PlannedCompaction(
                        source.job(),
                        execution,
                        source.generationSettings(),
                        source.warmTokenBudget(),
                        source.plan(),
                        source.candidates(),
                        source.coldCandidate());
        return new ReadyCompaction(replaced, ready.cancelled());
    }

    private static ReadyCompaction withPlan(
            ReadyCompaction ready, HierarchicalCompactionPlan plan) {
        PlannedCompaction source = ready.planned();
        PlannedCompaction replaced =
                new PlannedCompaction(
                        source.job(),
                        source.execution(),
                        source.generationSettings(),
                        source.warmTokenBudget(),
                        plan,
                        source.candidates(),
                        source.coldCandidate());
        return new ReadyCompaction(replaced, ready.cancelled());
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
        HierarchicalSummaryNodeRepository.class,
        HierarchicalCompactionJobRepository.class,
        HierarchicalDocumentCodec.class,
        RuntimeConfiguration.class
    })
    static class Application {}

    @TestConfiguration(proxyBeanMethods = false)
    static class RuntimeConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        IdGenerator idGenerator() {
            return new PrefixedUuidIdGenerator();
        }

        @Bean
        ContextExecutionControlPort executionControl() {
            return mock(ContextExecutionControlPort.class);
        }

        @Bean
        HierarchicalSummaryGenerator summaryGenerator() {
            return new HierarchicalSummaryGenerator(
                    mock(ContextSummaryModelPort.class),
                    new ContextSummaryDocumentValidator(),
                    new HierarchicalNodeKeyFactory());
        }

        @Bean
        HierarchicalCompactionPlanningTransaction planning(
                HierarchicalCompactionJobRepository jobs,
                IdGenerator ids,
                ContextExecutionControlPort executions,
                HierarchicalSummaryGenerator generator,
                ObjectMapper json) {
            return new HierarchicalCompactionPlanningTransaction(
                    mock(
                            com.datagent.agentv2.context.hierarchical.planning
                                    .BinarySummaryTreePlanner.class),
                    generator,
                    jobs,
                    ids,
                    executions,
                    json);
        }

        @Bean
        HierarchicalCandidateGenerationService generation(
                HierarchicalSummaryGenerator generator,
                HierarchicalSummaryNodeRepository nodes,
                HierarchicalCompactionJobRepository jobs,
                ContextExecutionControlPort executions) {
            return new HierarchicalCandidateGenerationService(
                    generator,
                    nodes,
                    jobs,
                    executions,
                    mock(HierarchicalGenerationSourceProvider.class));
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
