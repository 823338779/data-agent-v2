package com.datagent.agentv2.context.hierarchical.strategy;

import com.datagent.agentv2.context.hierarchical.configuration.HierarchicalContextProperties;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextState;
import com.datagent.agentv2.context.hierarchical.observability.HierarchicalContextMetrics;
import com.datagent.agentv2.context.hierarchical.observability.HierarchicalContextMetrics.CompactionOutcome;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalExecutionPolicyRepository;
import com.datagent.agentv2.context.hierarchical.planning.HierarchicalContextPolicy;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionCoordinator;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalConversationContextAssembler;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import com.datagent.agentv2.conversation.context.ConversationContextStrategy;
import com.datagent.agentv2.conversation.context.PreparedConversationContext;
import java.time.Duration;
import java.util.Objects;

/** Hierarchical 插件的唯一业务入口，先冻结 execution policy 再读取任何会话历史。 */
public final class HierarchicalConversationContextStrategy implements ConversationContextStrategy {

    public static final String STRATEGY_ID = "hierarchical";

    /** 冻结或读取 execution 首次持久化策略的仓储。 */
    private final HierarchicalExecutionPolicyRepository policies;

    /** 从冻结历史构造当前视图或发布视图的唯一 assembler。 */
    private final HierarchicalConversationContextAssembler assembler;

    /** 在超过软水位时执行恢复、生成和原子发布的协调器。 */
    private final HierarchicalCompactionCoordinator coordinator;

    /** 只接受低基数维度的插件观测入口。 */
    private final HierarchicalContextMetrics metrics;

    /** 当前服务配置只用于首次创建 execution 的不可变策略快照。 */
    private final HierarchicalContextPolicySnapshot configuredPolicy;

    public HierarchicalConversationContextStrategy(
            HierarchicalExecutionPolicyRepository policies,
            HierarchicalConversationContextAssembler assembler,
            HierarchicalCompactionCoordinator coordinator,
            HierarchicalContextMetrics metrics,
            HierarchicalContextProperties properties) {
        this.policies = Objects.requireNonNull(policies, "policies");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        configuredPolicy = Objects.requireNonNull(properties, "properties").snapshot();
    }

    @Override
    public String strategyId() {
        return STRATEGY_ID;
    }

    @Override
    public PreparedConversationContext prepare(ConversationContextRequest request) {
        ConversationContextRequest checkedRequest = Objects.requireNonNull(request, "request");

        // 必须是首个副作用：retry/takeover 只能读取首次持久化的 execution policy。
        HierarchicalContextPolicySnapshot frozen =
                policies.findOrCreate(checkedRequest.execution(), configuredPolicy);
        HierarchicalContextState state = assembler.loadAndEstimate(checkedRequest, frozen);
        metrics.recordInput(state.estimatedTokens(), checkedRequest.budget());
        HierarchicalContextPolicy policy = new HierarchicalContextPolicy(frozen);
        if (!policy.requiresCompaction(state.estimatedTokens(), checkedRequest.budget())) {
            PreparedConversationContext prepared = assembler.prepareCurrentView(state);
            recordPublishedState(state.checkpoint(), frozen);
            return prepared;
        }

        // 协调器负责恢复、生成和原子发布；策略只计时并重建最终正式视图。
        long startedAt = System.nanoTime();
        try {
            HierarchicalCompactionCoordinator.CompactionOutcome outcome =
                    coordinator.compactIfRequired(state);
            PreparedConversationContext prepared =
                    assembler.preparePublishedView(outcome.checkpoint(), checkedRequest, frozen);
            metrics.recordCompaction(
                    CompactionOutcome.SUCCEEDED,
                    Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAt)));
            recordPublishedState(outcome.checkpoint(), frozen);
            return prepared;
        } catch (RuntimeException failure) {
            metrics.recordCompaction(
                    CompactionOutcome.FAILED,
                    Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAt)));
            throw failure;
        }
    }

    private void recordPublishedState(
            HierarchicalCheckpoint checkpoint, HierarchicalContextPolicySnapshot policy) {
        metrics.recordActiveFrontier(assembler.activeFrontierCounts(checkpoint, policy));
        metrics.recordCheckpoint(
                checkpoint.summarizedUpToConversationMessageSequence(),
                checkpoint.strategyGeneration());
    }
}
