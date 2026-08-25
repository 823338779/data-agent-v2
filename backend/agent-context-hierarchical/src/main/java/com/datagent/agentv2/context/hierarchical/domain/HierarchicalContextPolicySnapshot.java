package com.datagent.agentv2.context.hierarchical.domain;

import com.datagent.agentv2.conversation.context.ContextBudget;

/** Hierarchical execution 首次使用时冻结的不可变策略参数。 */
public record HierarchicalContextPolicySnapshot(
        int policyVersion, double triggerRatio, double targetRatio) {

    private static final int RECENT_RAW_TURNS = 10;

    public HierarchicalContextPolicySnapshot {
        if (policyVersion <= 0
                || targetRatio <= 0.0d
                || targetRatio >= triggerRatio
                || triggerRatio >= 1.0d) {
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

    /** 返回算法固定保留的近期原文轮数；该常量不再伪装成逐快照可变字段。 */
    public int recentRawTurns() {
        return RECENT_RAW_TURNS;
    }
}
