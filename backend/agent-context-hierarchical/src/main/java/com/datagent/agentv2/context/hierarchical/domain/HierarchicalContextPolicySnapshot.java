package com.datagent.agentv2.context.hierarchical.domain;

import com.datagent.agentv2.conversation.context.ContextBudget;

/** Hierarchical execution 首次使用时冻结的不可变策略参数。 */
public record HierarchicalContextPolicySnapshot(
        int policyVersion,
        double triggerRatio,
        double targetRatio,
        int recentRawTurns,
        int summarySchemaVersion,
        int summaryPromptVersion) {

    public HierarchicalContextPolicySnapshot {
        if (policyVersion <= 0
                || targetRatio <= 0.0d
                || targetRatio >= triggerRatio
                || triggerRatio >= 1.0d
                || recentRawTurns != 10
                || summarySchemaVersion != 2
                || summaryPromptVersion != 2) {
            throw new IllegalArgumentException("Invalid hierarchical context policy");
        }
    }

    /** Returns the input-token threshold that starts hierarchical compaction. */
    public int softTriggerTokens(ContextBudget budget) {
        return (int) Math.floor(budget.contextWindowTokens() * triggerRatio);
    }

    /** Returns the post-compaction input-token target for the current model budget. */
    public int targetInputTokens(ContextBudget budget) {
        return (int) Math.floor(budget.contextWindowTokens() * targetRatio);
    }
}
