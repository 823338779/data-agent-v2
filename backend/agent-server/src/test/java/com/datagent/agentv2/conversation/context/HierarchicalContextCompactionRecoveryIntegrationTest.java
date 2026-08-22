package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCompactionJob;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionBoundaryHook;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionBoundaryHook.Boundary;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalConversationContextAssembler;
import com.datagent.agentv2.context.hierarchical.strategy.HierarchicalConversationContextStrategy;
import com.datagent.agentv2.conversation.exception.ExecutionOwnershipLostException;
import com.datagent.agentv2.conversation.runtime.AgentExecutionLeaseService;
import com.datagent.agentv2.platform.context.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** 从自然 durable 边界模拟进程终止，验收租约接管、候选复用和唯一发布。 */
@SpringBootTest(
        classes = HierarchicalContextCompactionIntegrationTest.TestApplication.class,
        properties = {
            "data-agent.context.strategy=hierarchical",
            "data-agent.runtime.scheduling-enabled=false"
        })
@Import(HierarchicalContextCompactionRecoveryIntegrationTest.FailpointConfiguration.class)
class HierarchicalContextCompactionRecoveryIntegrationTest
        extends HierarchicalContextIntegrationSupport {

    private static final int COMPLETED_TURNS = 24;
    private static final String NEW_OWNER_ID = "owner_hierarchical_takeover";

    @Autowired private HierarchicalConversationContextStrategy strategy;
    @Autowired private HierarchicalConversationContextAssembler assembler;
    @Autowired private AgentExecutionLeaseService leases;
    @Autowired private CrashBoundaryHook failpoint;

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
        failpoint.reset();
        summaryModel.reset();

        // 首次正常策略调用冻结 policy 并幂等创建 generation 0 checkpoint，不人工写插件表。
        TenantContext.call(TENANT_ID, () -> strategy.prepare(unlimitedRequest(OWNER_ID, 1L)));
        summaryModel.reset();
    }

    @ParameterizedTest(name = "takeover converges after natural crash at {0}")
    @EnumSource(Boundary.class)
    void takeoverConvergesFromNaturalDurableBoundary(Boundary crashBoundary) {
        ConversationContextRequest oldRequest = triggeredRequest(OWNER_ID, 1L);
        failpoint.arm(crashBoundary);

        assertThatThrownBy(() -> TenantContext.call(TENANT_ID, () -> strategy.prepare(oldRequest)))
                .isInstanceOf(SimulatedProcessCrash.class);

        DurablePlanCounts planned = durablePlanCounts();
        int generatedBeforeTakeover = summaryModel.invocationCount();
        assertDurableCrashState(crashBoundary, planned, generatedBeforeTakeover);
        assertThat(compactionEventTypes()).containsExactly("context_compaction_started");

        expireLease();
        AgentExecutionLeaseService.RecoveryResult takeover =
                TenantContext.call(TENANT_ID, () -> leases.takeoverNext(NEW_OWNER_ID));
        assertThat(takeover.execution()).isNotNull();
        assertThat(takeover.execution().ownerInstanceId()).isEqualTo(NEW_OWNER_ID);
        assertThat(takeover.execution().executionAttemptNumber()).isEqualTo(2L);

        // 新 owner claim 前，旧 attempt 已被 execution fence 拒绝且不能发布 generation。
        assertThatThrownBy(() -> TenantContext.call(TENANT_ID, () -> strategy.prepare(oldRequest)))
                .isInstanceOf(ExecutionOwnershipLostException.class);
        assertThat(checkpointRow(TENANT_ID, SESSION_ID)).containsEntry("strategy_generation", 0L);

        ConversationContextRequest newRequest = triggeredRequest(NEW_OWNER_ID, 2L);
        PreparedConversationContext recovered =
                TenantContext.call(TENANT_ID, () -> strategy.prepare(newRequest));
        PreparedConversationContext replayed =
                TenantContext.call(TENANT_ID, () -> strategy.prepare(newRequest));

        // PLANNED 生成全部、GENERATING 只补缺失、READY 不再调用模型；最终都只发布一次。
        assertThat(summaryModel.invocationCount()).isEqualTo(planned.totalModelCalls());
        assertThat(recovered.strategyGeneration()).isEqualTo(1L);
        assertThat(replayed.strategyGeneration()).isEqualTo(1L);
        assertThat(checkpointRow(TENANT_ID, SESSION_ID))
                .containsEntry("strategy_generation", 1L)
                .containsEntry("version", 1L);
        assertThat(compactionEventTypes())
                .containsExactly("context_compaction_started", "context_compaction_completed");
        assertThat(jobStatuses())
                .containsExactly(HierarchicalCompactionJob.Status.SUCCEEDED.name());
        assertThat(failedJobCount()).isZero();
    }

    private void assertDurableCrashState(
            Boundary boundary, DurablePlanCounts planned, int generatedBeforeTakeover) {
        switch (boundary) {
            case PLANNED -> {
                assertThat(jobStatuses())
                        .containsExactly(HierarchicalCompactionJob.Status.PLANNED.name());
                assertThat(durableNodeCount()).isZero();
                assertThat(generatedBeforeTakeover).isZero();
            }
            case GENERATING -> {
                assertThat(jobStatuses())
                        .containsExactly(HierarchicalCompactionJob.Status.GENERATING.name());
                assertThat(durableNodeCount()).isEqualTo(1);
                assertThat(generatedBeforeTakeover).isEqualTo(1);
            }
            case READY_TO_PUBLISH -> {
                assertThat(jobStatuses())
                        .containsExactly(HierarchicalCompactionJob.Status.READY_TO_PUBLISH.name());
                assertThat(durableNodeCount()).isEqualTo(planned.nodeCandidates());
                assertThat(generatedBeforeTakeover).isEqualTo(planned.totalModelCalls());
            }
        }
    }

    private ConversationContextRequest unlimitedRequest(String ownerId, long attempt) {
        return request(
                TENANT_ID,
                SESSION_ID,
                EXECUTION_ID,
                ownerId,
                attempt,
                completedBoundary(COMPLETED_TURNS) + 1L,
                new ContextBudget(1_000_000, 1, 1, 0),
                List.of(),
                List.of());
    }

    private ConversationContextRequest triggeredRequest(String ownerId, long attempt) {
        ConversationContextRequest measurement = unlimitedRequest(ownerId, attempt);
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
                completedBoundary(COMPLETED_TURNS) + 1L,
                budgetWithTrigger(actual - 1),
                List.of(),
                List.of());
    }

    private DurablePlanCounts durablePlanCounts() {
        return jdbc.queryForObject(
                "select jsonb_array_length(plan_document -> 'candidates'), case when plan_document -> 'coldCandidate' = 'null'::jsonb then 0 else 1 end from conversation_context_hierarchical_compaction_job where tenant_id = ? and execution_id = ?",
                (result, row) -> new DurablePlanCounts(result.getInt(1), result.getInt(2)),
                TENANT_ID,
                EXECUTION_ID);
    }

    private List<String> compactionEventTypes() {
        return jdbc.queryForList(
                "select type from agent_execution_event where tenant_id = ? and execution_id = ? and type like 'context_compaction_%' order by execution_event_sequence",
                String.class, TENANT_ID, EXECUTION_ID);
    }

    private List<String> jobStatuses() {
        return jdbc.queryForList(
                "select status from conversation_context_hierarchical_compaction_job where tenant_id = ? and execution_id = ? order by created_at",
                String.class,
                TENANT_ID,
                EXECUTION_ID);
    }

    private int durableNodeCount() {
        return jdbc.queryForObject(
                "select count(*) from conversation_context_hierarchical_summary_node where tenant_id = ? and conversation_session_id = ?",
                Integer.class,
                TENANT_ID,
                SESSION_ID);
    }

    private int failedJobCount() {
        return jdbc.queryForObject(
                "select count(*) from conversation_context_hierarchical_compaction_job where tenant_id = ? and execution_id = ? and status = 'FAILED'",
                Integer.class,
                TENANT_ID,
                EXECUTION_ID);
    }

    private void expireLease() {
        jdbc.update(
                "update agent_execution set lease_expires_at = ? where tenant_id = ? and execution_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)),
                TENANT_ID,
                EXECUTION_ID);
    }

    private record DurablePlanCounts(int nodeCandidates, int coldCandidates) {
        int totalModelCalls() {
            return nodeCandidates + coldCandidates;
        }
    }

    /** 每个参数化场景只在指定 durable 边界抛出一次，模拟进程直接终止。 */
    static final class CrashBoundaryHook implements HierarchicalCompactionBoundaryHook {

        private final AtomicReference<Boundary> armed = new AtomicReference<>();

        void arm(Boundary boundary) {
            armed.set(boundary);
        }

        void reset() {
            armed.set(null);
        }

        @Override
        public void reached(Boundary boundary, int durableCandidateCount) {
            boolean exactBoundary = boundary != Boundary.GENERATING || durableCandidateCount == 1;
            if (exactBoundary && armed.compareAndSet(boundary, null)) {
                throw new SimulatedProcessCrash();
            }
        }
    }

    /** 与业务失败区分的测试进程终止信号，不应生成 FAILED job 或 execution event。 */
    static final class SimulatedProcessCrash extends RuntimeException {}

    @TestConfiguration(proxyBeanMethods = false)
    static class FailpointConfiguration {

        @Bean
        CrashBoundaryHook hierarchicalCrashBoundaryHook() {
            return new CrashBoundaryHook();
        }
    }
}
