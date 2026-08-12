package com.datagent.agentv2.context.lsm.strategy;

import com.datagent.agentv2.context.lsm.observability.LsmContextMetrics;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmExecutionPolicyRepository;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator;
import com.datagent.agentv2.context.lsm.runtime.LsmConversationContextAssembler;
import com.datagent.agentv2.conversation.context.ContextPolicySnapshot;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import com.datagent.agentv2.conversation.context.ConversationContextStrategy;
import com.datagent.agentv2.conversation.context.PreparedConversationContext;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** LSM 插件的唯一策略入口，负责先冻结 execution policy 再执行预算决策。 */
public class LsmConversationContextStrategy implements ConversationContextStrategy {

    public static final String STRATEGY_ID = "lsm";

    private final LsmExecutionPolicyRepository policies;
    private final LsmConversationContextAssembler assembler;
    private final LsmCompactionCoordinator coordinator;
    private final LsmContextMetrics metrics;

    public LsmConversationContextStrategy(
            LsmExecutionPolicyRepository policies,
            LsmConversationContextAssembler assembler,
            LsmCompactionCoordinator coordinator) {
        this(policies, assembler, coordinator, LsmContextMetrics.isolated());
    }

    public LsmConversationContextStrategy(
            LsmExecutionPolicyRepository policies,
            LsmConversationContextAssembler assembler,
            LsmCompactionCoordinator coordinator,
            LsmContextMetrics metrics) {
        this.policies = Objects.requireNonNull(policies, "policies");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public String strategyId() {
        return STRATEGY_ID;
    }

    @Override
    public PreparedConversationContext prepare(ConversationContextRequest request) {
        // 必须是首个副作用：即使不足 75%，retry/takeover 也只能读取同一策略快照。
        ContextPolicySnapshot frozen =
                policies.findOrCreateExecutionPolicy(request.execution(), request.policy());
        ConversationContextRequest frozenRequest = request.withPolicy(frozen);
        LsmConversationContextAssembler.ContextState state =
                assembler.loadAndEstimate(frozenRequest);
        recordInputState(state);
        if (state.estimatedTokens() <= request.budget().softTriggerTokens()) {
            return assembler.prepareCurrentView(state);
        }
        LsmCompactionCoordinator.CompactionOutcome outcome =
                coordinator.compactIfRequired(assembler.toCompactionRequest(state));
        return assembler.preparePublishedView(outcome.manifest(), frozenRequest);
    }

    private void recordInputState(LsmConversationContextAssembler.ContextState state) {
        double utilization =
                (double) state.estimatedTokens() / state.request().budget().contextWindowTokens();
        metrics.recordInput(state.estimatedTokens(), utilization);

        // 当前 manifest 的层级分布只聚合活动节点，不统计已经退出发布视图的历史段。
        Set<String> activeSegmentIds = new HashSet<>(state.manifest().activeSegmentIds());
        if (state.manifest().activeRootSegmentId() != null) {
            activeSegmentIds.add(state.manifest().activeRootSegmentId());
        }
        Map<Integer, Integer> countsByLevel = new HashMap<>();
        state.persistedSegments().stream()
                .filter(segment -> activeSegmentIds.contains(segment.getSummarySegmentId()))
                .forEach(segment -> countsByLevel.merge(segment.getLevel(), 1, Integer::sum));
        metrics.recordActiveSegments(countsByLevel);
    }
}
