package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalConversationContextAssembler;
import com.datagent.agentv2.context.hierarchical.strategy.HierarchicalConversationContextStrategy;
import com.datagent.agentv2.conversation.exception.ExecutionOwnershipLostException;
import com.datagent.agentv2.conversation.runtime.AgentExecutionLeaseService;
import com.datagent.agentv2.platform.context.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 使用完整宿主协调器验收三个 durable 崩溃边界的租约接管和旧 owner fencing。 */
@SpringBootTest(
        classes = HierarchicalContextCompactionIntegrationTest.TestApplication.class,
        properties = {
            "data-agent.context.strategy=hierarchical",
            "data-agent.runtime.scheduling-enabled=false",
            "data-agent.auth.bootstrap.enabled=false"
        })
class HierarchicalContextCompactionRecoveryIntegrationTest
        extends HierarchicalContextIntegrationSupport {

    private static final int COMPLETED_TURNS = 24;
    private static final String NEW_OWNER_ID = "owner_hierarchical_takeover";

    @Autowired private HierarchicalConversationContextStrategy strategy;
    @Autowired private HierarchicalConversationContextAssembler assembler;
    @Autowired private AgentExecutionLeaseService leases;

    @Autowired
    private HierarchicalContextCompactionIntegrationTest.DeterministicSummaryModel summaryModel;

    @BeforeEach
    void seedRecoverableConversation() {
        resetDatabase();
        seedTenantSession(TENANT_ID, USER_ID, SESSION_ID);
        seedCompletedHistory(TENANT_ID, USER_ID, SESSION_ID, COMPLETED_TURNS, true);
        seedRunningExecution(
                TENANT_ID,
                USER_ID,
                SESSION_ID,
                EXECUTION_ID,
                OWNER_ID,
                1L,
                completedBoundary(COMPLETED_TURNS) + 1L);
        seedInitialCheckpoint(TENANT_ID, SESSION_ID);
        summaryModel.reset();
    }

    @ParameterizedTest(name = "takeover converges after crash at {0}")
    @ValueSource(strings = {"PLANNED", "GENERATING", "READY_TO_PUBLISH"})
    void takeoverConvergesToOnePublicationAndFencesOldOwner(String crashStatus) {
        ConversationContextRequest oldRequest = triggeredRequest(OWNER_ID, 1L);

        // 先让真实链路生成一份完整 durable plan/candidates，再回放到指定崩溃边界。
        PreparedConversationContext first =
                TenantContext.call(TENANT_ID, () -> strategy.prepare(oldRequest));
        assertThat(first.strategyGeneration()).isEqualTo(1L);
        int generatedBeforeTakeover = summaryModel.invocationCount();
        rewindPublishedCycleTo(crashStatus);

        jdbc.update(
                "update agent_execution set lease_expires_at = ? where tenant_id = ? and execution_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)),
                TENANT_ID,
                EXECUTION_ID);
        AgentExecutionLeaseService.RecoveryResult takeover =
                TenantContext.call(TENANT_ID, () -> leases.takeoverNext(NEW_OWNER_ID));
        assertThat(takeover.execution()).isNotNull();
        assertThat(takeover.execution().ownerInstanceId()).isEqualTo(NEW_OWNER_ID);
        assertThat(takeover.execution().executionAttemptNumber()).isEqualTo(2L);

        // takeover 后旧 attempt 在恢复或发布前即被 execution fence 拒绝，不能改写 checkpoint。
        assertThatThrownBy(() -> TenantContext.call(TENANT_ID, () -> strategy.prepare(oldRequest)))
                .isInstanceOf(ExecutionOwnershipLostException.class);
        assertThat(checkpointRow(TENANT_ID, SESSION_ID)).containsEntry("strategy_generation", 0L);

        ConversationContextRequest newRequest = triggeredRequest(NEW_OWNER_ID, 2L);
        PreparedConversationContext recovered =
                TenantContext.call(TENANT_ID, () -> strategy.prepare(newRequest));
        PreparedConversationContext replayed =
                TenantContext.call(TENANT_ID, () -> strategy.prepare(newRequest));

        // 三个边界都复用相同 durable candidates，最终只发布一个 generation。
        assertThat(summaryModel.invocationCount()).isEqualTo(generatedBeforeTakeover);
        assertThat(recovered.strategyGeneration()).isEqualTo(1L);
        assertThat(replayed.strategyGeneration()).isEqualTo(1L);
        assertThat(checkpointRow(TENANT_ID, SESSION_ID))
                .containsEntry("strategy_generation", 1L)
                .containsEntry("version", 1L);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from conversation_context_hierarchical_compaction_job where tenant_id = ? and execution_id = ? and status = 'SUCCEEDED'",
                                Integer.class,
                                TENANT_ID,
                                EXECUTION_ID))
                .isEqualTo(1);
    }

    private ConversationContextRequest triggeredRequest(String ownerId, long attempt) {
        long boundary = completedBoundary(COMPLETED_TURNS) + 1L;
        ConversationContextRequest measurement =
                request(
                        TENANT_ID,
                        SESSION_ID,
                        EXECUTION_ID,
                        ownerId,
                        attempt,
                        boundary,
                        new ContextBudget(1_000_000, 1, 1, 0),
                        List.of(),
                        List.of());
        int actual =
                TenantContext.call(
                                TENANT_ID,
                                () -> assembler.loadAndEstimate(measurement, DEFAULT_POLICY))
                        .estimatedTokens();
        return request(
                TENANT_ID,
                SESSION_ID,
                EXECUTION_ID,
                ownerId,
                attempt,
                boundary,
                budgetWithTrigger(actual - 1),
                List.of(),
                List.of());
    }

    private void rewindPublishedCycleTo(String status) {
        // 测试回放只改变插件生命周期元数据；durable plan、节点和 Cold 候选保持真实生成结果。
        jdbc.update(
                "update conversation_context_hierarchical_checkpoint set cold_summary_document = ?::jsonb, summarized_up_to_conversation_message_sequence = 0, summarized_completed_turn_count = 0, estimated_token_count = 0, active_frontier_document = ?::jsonb, strategy_generation = 0, version = 0 where tenant_id = ? and conversation_session_id = ?",
                EMPTY_SUMMARY,
                EMPTY_FRONTIER,
                TENANT_ID,
                SESSION_ID);
        jdbc.update(
                "update conversation_context_hierarchical_compaction_job set status = ?, owner_instance_id = ?, execution_attempt = 1, completed_at = null, failure_code = null where tenant_id = ? and execution_id = ?",
                status,
                OWNER_ID,
                TENANT_ID,
                EXECUTION_ID);
        jdbc.update(
                "delete from agent_execution_event where tenant_id = ? and execution_id = ?",
                TENANT_ID,
                EXECUTION_ID);
        jdbc.update(
                "update conversation_session set execution_event_sequence = 0 where tenant_id = ? and conversation_session_id = ?",
                TENANT_ID,
                SESSION_ID);
    }
}
