package com.datagent.agentv2.context.lsm.strategy;

import com.datagent.agentv2.context.lsm.persistence.repository.LsmExecutionPolicyRepository;
import com.datagent.agentv2.context.lsm.runtime.LsmCompactionCoordinator;
import com.datagent.agentv2.context.lsm.runtime.LsmConversationContextAssembler;
import com.datagent.agentv2.conversation.context.ContextPolicySnapshot;
import com.datagent.agentv2.conversation.context.ConversationContextRequest;
import com.datagent.agentv2.conversation.context.ConversationContextStrategy;
import com.datagent.agentv2.conversation.context.PreparedConversationContext;

/** LSM 插件的唯一策略入口，负责先冻结 execution policy 再执行预算决策。 */
public class LsmConversationContextStrategy implements ConversationContextStrategy {

    public static final String STRATEGY_ID = "lsm";

    private final LsmExecutionPolicyRepository policies;
    private final LsmConversationContextAssembler assembler;
    private final LsmCompactionCoordinator coordinator;

    public LsmConversationContextStrategy(
            LsmExecutionPolicyRepository policies,
            LsmConversationContextAssembler assembler,
            LsmCompactionCoordinator coordinator) {
        this.policies = policies;
        this.assembler = assembler;
        this.coordinator = coordinator;
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
        if (state.estimatedTokens() <= request.budget().softTriggerTokens()) {
            return assembler.prepareCurrentView(state);
        }
        LsmCompactionCoordinator.CompactionOutcome outcome =
                coordinator.compactIfRequired(assembler.toCompactionRequest(state));
        return assembler.preparePublishedView(outcome.manifest(), frozenRequest);
    }
}
