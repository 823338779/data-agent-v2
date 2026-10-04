package com.datagent.agentv2.context.hierarchical.strategy;

import com.datagent.agentv2.context.hierarchical.configuration.HierarchicalContextProperties;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextState;
import com.datagent.agentv2.context.hierarchical.observability.HierarchicalContextMetrics;
import com.datagent.agentv2.context.hierarchical.observability.HierarchicalContextMetrics.CompactionOutcome;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCheckpointRepository;
import com.datagent.agentv2.context.hierarchical.planning.HierarchicalContextPolicy;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalCompactionCoordinator;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalConversationContextAssembler;
import com.datagent.agentv2.conversation.context.ContextSummaryModelIdentity;
import com.datagent.agentv2.conversation.context.ContextSummaryModelPort;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import com.datagent.agentv2.conversation.context.ConversationContextStrategy;
import com.datagent.agentv2.conversation.context.PreparedConversationContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Duration;
import java.util.Objects;

/** Hierarchical 插件的唯一业务入口，使用当前进程配置同步压缩会话历史。 */
public final class HierarchicalConversationContextStrategy implements ConversationContextStrategy {

    public static final String STRATEGY_ID = "hierarchical";
    private static final String CHECKPOINT_ID_PREFIX = "hierarchical_checkpoint";

    /** 为新会话幂等建立 generation 0 基线的私有仓储。 */
    private final HierarchicalCheckpointRepository checkpoints;

    /** 从冻结历史构造当前视图或发布视图的唯一 assembler。 */
    private final HierarchicalConversationContextAssembler assembler;

    /** 在超过软水位时执行恢复、生成和原子发布的协调器。 */
    private final HierarchicalCompactionCoordinator coordinator;

    /** 只接受低基数维度的插件观测入口。 */
    private final HierarchicalContextMetrics metrics;

    /** checkpoint 冻结摘要模型身份，后续 generation 不得混用其他模型。 */
    private final ContextSummaryModelPort summaryModel;

    /** 为首次 checkpoint 生成无业务含义的唯一持久化 ID。 */
    private final IdGenerator ids;

    /** 当前服务配置只用于首次创建 execution 的不可变策略快照。 */
    private final HierarchicalContextPolicySnapshot configuredPolicy;

    /** 默认运行的 V3 引擎；旧构造入口仅用于 V2 兼容验证。 */
    private final com.datagent.agentv2.context.hierarchical.runtime.HierarchicalStepContextEngine
            stepEngine;

    public HierarchicalConversationContextStrategy(
            HierarchicalCheckpointRepository checkpoints,
            HierarchicalConversationContextAssembler assembler,
            HierarchicalCompactionCoordinator coordinator,
            HierarchicalContextMetrics metrics,
            ContextSummaryModelPort summaryModel,
            IdGenerator ids,
            HierarchicalContextProperties properties) {
        this(checkpoints, assembler, coordinator, metrics, summaryModel, ids, properties, null);
    }

    public HierarchicalConversationContextStrategy(
            HierarchicalCheckpointRepository checkpoints,
            HierarchicalConversationContextAssembler assembler,
            HierarchicalCompactionCoordinator coordinator,
            HierarchicalContextMetrics metrics,
            ContextSummaryModelPort summaryModel,
            IdGenerator ids,
            HierarchicalContextProperties properties,
            com.datagent.agentv2.context.hierarchical.runtime.HierarchicalStepContextEngine
                    stepEngine) {
        this.stepEngine = stepEngine;
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.summaryModel = Objects.requireNonNull(summaryModel, "summaryModel");
        this.ids = Objects.requireNonNull(ids, "ids");
        configuredPolicy = Objects.requireNonNull(properties, "properties").snapshot();
    }

    @Override
    public String strategyId() {
        return STRATEGY_ID;
    }

    @Override
    public PreparedConversationContext prepare(ConversationContextRequest request) {
        if (stepEngine != null) {
            return stepEngine.prepare(request);
        }
        ConversationContextRequest checkedRequest = Objects.requireNonNull(request, "request");

        HierarchicalContextPolicySnapshot frozen = configuredPolicy;
        ContextSummaryModelIdentity model =
                Objects.requireNonNull(summaryModel.identity(), "summary model identity");
        ensureInitialCheckpoint(checkedRequest, frozen, model);
        HierarchicalContextState state = assembler.loadAndEstimate(checkedRequest, frozen);
        metrics.recordInput(state.estimatedTokens(), checkedRequest.budget());
        HierarchicalContextPolicy policy = new HierarchicalContextPolicy(frozen);
        if (!policy.requiresCompaction(state.estimatedTokens(), checkedRequest.budget())) {
            PreparedConversationContext prepared = assembler.prepareCurrentView(state);
            recordPublishedState(state.checkpoint(), frozen);
            return prepared;
        }

        // 协调器同步生成并原子发布；策略只计时并重建最终正式视图。
        long startedAt = System.nanoTime();
        try {
            HierarchicalCompactionCoordinator.CompactionOutcome outcome =
                    coordinator.compactIfRequired(state, model);
            PreparedConversationContext prepared =
                    outcome.compacted()
                            ? assembler.preparePublishedView(
                                    outcome.checkpoint(), checkedRequest, frozen)
                            : assembler.prepareCurrentView(state);
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

    /** 唯一键竞争由 repository 收敛；首次历史读取前所有调用都能观察到同一基线。 */
    private void ensureInitialCheckpoint(
            ConversationContextRequest request,
            HierarchicalContextPolicySnapshot policy,
            ContextSummaryModelIdentity model) {
        checkpoints.create(
                new HierarchicalCheckpoint(
                        ids.next(CHECKPOINT_ID_PREFIX),
                        request.execution().conversationSessionId(),
                        null,
                        0L,
                        0L,
                        0L));
    }

    private void recordPublishedState(
            HierarchicalCheckpoint checkpoint, HierarchicalContextPolicySnapshot policy) {
        metrics.recordCheckpoint(
                checkpoint.evaluatedUpToConversationMessageSequence(),
                checkpoint.strategyGeneration());
    }

    @Override
    public void closeExecution(String executionId) {
        if (stepEngine != null) {
            stepEngine.closeExecution(executionId);
        }
    }
}
