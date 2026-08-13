package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalSummaryNode;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCompactionJobRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalExecutionPolicyRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalSummaryNodeRepository;
import com.datagent.agentv2.platform.context.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 验收相同 session 业务 ID 下四类 hierarchical 私有数据的租户隔离。 */
@SpringBootTest(
        classes = HierarchicalContextCompactionIntegrationTest.TestApplication.class,
        properties = {
            "data-agent.context.strategy=hierarchical",
            "data-agent.runtime.scheduling-enabled=false",
            "data-agent.auth.bootstrap.enabled=false"
        })
class HierarchicalContextCompactionMultiTenantIntegrationTest
        extends HierarchicalContextIntegrationSupport {

    private static final String TENANT_A = "tenant_hierarchical_a";
    private static final String TENANT_B = "tenant_hierarchical_b";
    private static final String USER_A = "user_hierarchical_a";
    private static final String USER_B = "user_hierarchical_b";
    private static final String SHARED_SESSION_ID = "shared_hierarchical_session";
    private static final String EXECUTION_A = "execution_hierarchical_a";
    private static final String EXECUTION_B = "execution_hierarchical_b";
    private static final String NODE_A = "hierarchical-node-a";
    private static final String NODE_B = "hierarchical-node-b";
    private static final String JOB_A = "hierarchical-job-a";
    private static final String JOB_B = "hierarchical-job-b";
    private static final String PLAN_DOCUMENT = "{\"schemaVersion\":3}";

    @Autowired private HierarchicalExecutionPolicyRepository policies;
    @Autowired private HierarchicalSummaryNodeRepository nodes;
    @Autowired private HierarchicalCompactionJobRepository jobs;

    @BeforeEach
    void seedTwoTenantsWithTheSameSessionId() {
        resetDatabase();
        seedTenantSession(TENANT_A, USER_A, SHARED_SESSION_ID);
        seedTenantSession(TENANT_B, USER_B, SHARED_SESSION_ID);

        TenantContext.run(
                TENANT_A,
                () -> {
                    policies.findOrCreate(execution(TENANT_A, EXECUTION_A), DEFAULT_POLICY);
                    checkpoints.create(checkpoint(TENANT_A, 11L));
                    nodes.insertOrSelect(node(NODE_A, "tenant-a-summary"));
                });
        TenantContext.run(
                TENANT_B,
                () -> {
                    policies.findOrCreate(
                            execution(TENANT_B, EXECUTION_B),
                            new HierarchicalContextPolicySnapshot(1, 0.75d, 0.70d, 12, 100, 1, 1));
                    checkpoints.create(checkpoint(TENANT_B, 22L));
                    nodes.insertOrSelect(node(NODE_B, "tenant-b-summary"));
                });
        insertJob(TENANT_A, EXECUTION_A, JOB_A);
        insertJob(TENANT_B, EXECUTION_B, JOB_B);
    }

    @Test
    void policyNodeCheckpointAndJobReadsNeverCrossTenantBoundary() {
        HierarchicalContextPolicySnapshot frozenA =
                TenantContext.call(
                        TENANT_A,
                        () ->
                                policies.findOrCreate(
                                        execution(TENANT_A, EXECUTION_A),
                                        new HierarchicalContextPolicySnapshot(
                                                1, 0.75d, 0.70d, 99, 100, 1, 1)));
        HierarchicalContextPolicySnapshot frozenB =
                TenantContext.call(
                        TENANT_B,
                        () ->
                                policies.findOrCreate(
                                        execution(TENANT_B, EXECUTION_B), DEFAULT_POLICY));

        assertThat(frozenA.recentRawTurns()).isEqualTo(10);
        assertThat(frozenB.recentRawTurns()).isEqualTo(12);
        assertThat(
                        TenantContext.call(
                                        TENANT_A,
                                        () -> checkpoints.findCheckpoint(SHARED_SESSION_ID))
                                .summarizedCompletedTurnCount())
                .isEqualTo(11L);
        assertThat(
                        TenantContext.call(
                                        TENANT_B,
                                        () -> checkpoints.findCheckpoint(SHARED_SESSION_ID))
                                .summarizedCompletedTurnCount())
                .isEqualTo(22L);

        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () -> nodes.findByIds(SHARED_SESSION_ID, List.of(NODE_A))))
                .extracting(HierarchicalSummaryNode::nodeId)
                .containsExactly(NODE_A);
        assertThat(
                        TenantContext.call(
                                TENANT_A,
                                () -> nodes.findByIds(SHARED_SESSION_ID, List.of(NODE_B))))
                .isEmpty();
        assertThat(
                        TenantContext.call(
                                TENANT_B,
                                () -> nodes.findByIds(SHARED_SESSION_ID, List.of(NODE_B))))
                .extracting(HierarchicalSummaryNode::nodeId)
                .containsExactly(NODE_B);
        assertThat(
                        TenantContext.call(
                                TENANT_B,
                                () -> nodes.findByIds(SHARED_SESSION_ID, List.of(NODE_A))))
                .isEmpty();

        assertThat(TenantContext.call(TENANT_A, () -> jobs.findJob(JOB_A))).isNotNull();
        assertThat(TenantContext.call(TENANT_A, () -> jobs.findJob(JOB_B))).isNull();
        assertThat(TenantContext.call(TENANT_B, () -> jobs.findJob(JOB_B))).isNotNull();
        assertThat(TenantContext.call(TENANT_B, () -> jobs.findJob(JOB_A))).isNull();

        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from conversation_context_hierarchical_execution_policy where conversation_session_id = ?",
                                Integer.class,
                                SHARED_SESSION_ID))
                .isEqualTo(2);

        // V17 仅移除全局 session 业务键；复合唯一键及所有引用会话的 FK 继续绑定 tenant。
        assertThat(
                        jdbc.queryForObject(
                                "select pg_get_constraintdef(oid) from pg_constraint where conname = 'uq_conversation_session_tenant_conversation_session_id'",
                                String.class))
                .contains("UNIQUE (tenant_id, conversation_session_id)");
        assertThat(
                        jdbc.queryForList(
                                "select pg_get_constraintdef(oid) from pg_constraint where contype = 'f' and confrelid = 'conversation_session'::regclass",
                                String.class))
                .isNotEmpty()
                .allSatisfy(
                        definition ->
                                assertThat(definition)
                                        .contains(
                                                "FOREIGN KEY (tenant_id, conversation_session_id)",
                                                "REFERENCES conversation_session(tenant_id, conversation_session_id)"));
    }

    private static ContextExecutionRef execution(String tenantId, String executionId) {
        return new ContextExecutionRef(
                tenantId, SHARED_SESSION_ID, executionId, "owner-" + tenantId, 1L, 0L);
    }

    private static HierarchicalCheckpoint checkpoint(String tenantId, long summarizedTurns) {
        return new HierarchicalCheckpoint(
                "checkpoint-" + tenantId,
                SHARED_SESSION_ID,
                EMPTY_SUMMARY,
                summarizedTurns * 2L,
                summarizedTurns,
                10,
                1,
                1,
                MODEL_PROVIDER,
                MODEL_NAME,
                EMPTY_FRONTIER,
                0L,
                0L);
    }

    private static HierarchicalSummaryNode node(String nodeId, String fact) {
        String document =
                """
                {"schemaVersion":1,"coverage":{"startTurn":1,"endTurn":1,"startMessageSequence":1,"endMessageSequence":2},"confirmedFacts":[{"statement":"%s","sourceRanges":[{"startMessageSequence":1,"endMessageSequence":2}]}],"workingHypotheses":[],"decisions":[],"rejectedApproaches":[],"toolFindings":[],"nextSteps":[],"openQuestions":[]}
                """
                        .formatted(fact);
        return new HierarchicalSummaryNode(
                nodeId,
                SHARED_SESSION_ID,
                0,
                1L,
                1L,
                2L,
                null,
                null,
                document,
                40,
                1,
                1,
                MODEL_PROVIDER,
                MODEL_NAME);
    }

    private void insertJob(String tenantId, String executionId, String jobId) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                "insert into conversation_context_hierarchical_compaction_job (hierarchical_compaction_job_id, tenant_id, conversation_session_id, execution_id, execution_attempt, owner_instance_id, input_up_to_message_sequence, base_checkpoint_version, base_strategy_generation, plan_document, status, created_at, created_by, updated_at, updated_by, deleted, version) values (?, ?, ?, ?, 1, ?, 0, 0, 0, ?::jsonb, 'PLANNED', ?, 'test', ?, 'test', false, 0)",
                jobId,
                tenantId,
                SHARED_SESSION_ID,
                executionId,
                "owner-" + tenantId,
                PLAN_DOCUMENT,
                now,
                now);
    }
}
