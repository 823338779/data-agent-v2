package com.datagent.agentv2.context.lsm.strategy;

import com.datagent.agentv2.context.lsm.configuration.LsmContextProperties;
import com.datagent.agentv2.context.lsm.domain.LsmContextManifest;
import com.datagent.agentv2.context.lsm.domain.LsmContextPolicySnapshot;
import com.datagent.agentv2.context.lsm.observability.LsmContextMetrics;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator;
import com.datagent.agentv2.context.lsm.runtime.LsmConversationContextAssembler;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import com.datagent.agentv2.conversation.context.ConversationContextStrategy;
import com.datagent.agentv2.conversation.context.PreparedConversationContext;
import java.util.Objects;

/** LSM 插件的唯一策略入口，负责先冻结 execution policy 再执行预算决策。 */
public class LsmConversationContextStrategy implements ConversationContextStrategy {

    public static final String STRATEGY_ID = "lsm";

    private final LsmConversationContextAssembler assembler;
    private final LsmCompactionCoordinator coordinator;
    private final LsmContextMetrics metrics;

    /** 首次访问 execution 时用于创建冻结策略的当前服务配置。 */
    private final LsmContextProperties properties;

    public LsmConversationContextStrategy(
            LsmConversationContextAssembler assembler,
            LsmCompactionCoordinator coordinator,
            LsmContextProperties properties) {
        this(assembler, coordinator, LsmContextMetrics.isolated(), properties);
    }

    public LsmConversationContextStrategy(
            LsmConversationContextAssembler assembler,
            LsmCompactionCoordinator coordinator,
            LsmContextMetrics metrics,
            LsmContextProperties properties) {
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public String strategyId() {
        return STRATEGY_ID;
    }

    @Override
    public PreparedConversationContext prepare(ConversationContextRequest request) {
        // 单次准备全程使用同一份当前配置快照。
        LsmContextPolicySnapshot frozen = properties.snapshot();
        LsmConversationContextAssembler.ContextState state =
                assembler.loadAndEstimate(request, frozen);
        recordInputPressure(state);
        if (state.estimatedTokens() <= frozen.softTriggerTokens(request.budget())) {
            PreparedConversationContext prepared = assembler.prepareCurrentView(state);
            recordActiveSegments(request, state.manifest());
            return prepared;
        }
        LsmCompactionCoordinator.CompactionOutcome outcome =
                coordinator.compactIfRequired(assembler.toCompactionRequest(state));
        PreparedConversationContext prepared =
                assembler.preparePublishedView(outcome.manifest(), request, frozen);
        recordActiveSegments(request, outcome.manifest());
        return prepared;
    }

    private void recordInputPressure(LsmConversationContextAssembler.ContextState state) {
        double utilization =
                (double) state.estimatedTokens() / state.request().budget().contextWindowTokens();
        metrics.recordInput(state.estimatedTokens(), utilization);
    }

    private void recordActiveSegments(
            ConversationContextRequest request, LsmContextManifest finalManifest) {
        metrics.recordActiveSegments(
                request.execution().conversationSessionId(),
                assembler.activeSegmentCounts(finalManifest));
    }
}
