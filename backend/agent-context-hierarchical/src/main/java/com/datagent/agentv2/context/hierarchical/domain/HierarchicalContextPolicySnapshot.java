package com.datagent.agentv2.context.hierarchical.domain;

import com.datagent.agentv2.conversation.context.ContextBudget;

/** Hierarchical execution 首次使用时冻结的不可变策略参数。 */
public record HierarchicalContextPolicySnapshot(
        int policyVersion,
        double triggerRatio,
        double targetRatio,
        int recentRawTurns,
        int warmWindowTurns,
        int summarySchemaVersion,
        int summaryPromptVersion) {

    /** Returns the input-token threshold that starts hierarchical compaction. */
    public int softTriggerTokens(ContextBudget budget) {
        return (int) Math.floor(budget.contextWindowTokens() * triggerRatio);
    }

    /** Returns the post-compaction input-token target for the current model budget. */
    public int targetInputTokens(ContextBudget budget) {
        return (int) Math.floor(budget.contextWindowTokens() * targetRatio);
    }
}
