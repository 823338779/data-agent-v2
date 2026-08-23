package com.datagent.agentv2.context.hierarchical.planning;

import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.conversation.context.ContextBudget;
import java.util.Objects;

/** 将冻结策略的 75% 触发、70% 目标和物理硬边界集中为确定性判断。 */
public final class HierarchicalContextPolicy {

    private final HierarchicalContextPolicySnapshot snapshot;

    /** 绑定一次 execution 已冻结的策略参数。 */
    public HierarchicalContextPolicy(HierarchicalContextPolicySnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        if (!(snapshot.targetRatio() > 0.0d
                        && snapshot.targetRatio() < snapshot.triggerRatio()
                        && snapshot.triggerRatio() < 1.0d)
                || snapshot.recentRawTurns() != 10) {
            throw new IllegalArgumentException("Invalid hierarchical context policy");
        }
    }

    /** 返回超过后才触发压缩的软水位。 */
    public int softTriggerTokens(ContextBudget budget) {
        ContextBudget checkedBudget = validatedBudget(budget);
        return snapshot.softTriggerTokens(checkedBudget);
    }

    /** 返回压缩正常情况下应达到的目标输入水位。 */
    public int targetInputTokens(ContextBudget budget) {
        ContextBudget checkedBudget = validatedBudget(budget);
        return snapshot.targetInputTokens(checkedBudget);
    }

    /** 返回为输出和安全余量预留空间后的物理输入硬上限。 */
    public int hardInputLimitTokens(ContextBudget budget) {
        return validatedBudget(budget).hardInputLimitTokens();
    }

    /** 仅当输入严格超过软水位时创建压缩计划。 */
    public boolean requiresCompaction(int estimatedInputTokens, ContextBudget budget) {
        if (estimatedInputTokens < 0) {
            throw new IllegalArgumentException("estimatedInputTokens must not be negative");
        }
        return estimatedInputTokens > softTriggerTokens(budget);
    }

    /** 暴露已冻结的近期原文轮数，供边界规划复用。 */
    public int recentRawTurns() {
        return snapshot.recentRawTurns();
    }

    private ContextBudget validatedBudget(ContextBudget budget) {
        ContextBudget checked = Objects.requireNonNull(budget, "budget");
        if (checked.hardInputLimitTokens() <= snapshot.softTriggerTokens(checked)) {
            throw new IllegalArgumentException("Hard input limit must exceed the soft trigger");
        }
        return checked;
    }
}
