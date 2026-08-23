package com.datagent.agentv2.context.lsm.planning;

import com.datagent.agentv2.context.lsm.domain.LsmContextPolicySnapshot;
import com.datagent.agentv2.conversation.context.ContextBudget;
import java.util.Objects;

/** 将上下文预算的软触发、目标水位和硬边界集中为确定性决策。 */
public final class LsmContextPolicy {

    /** 仅超过 75% 软触发线时才允许创建新的压缩计划。 */
    public boolean requiresCompaction(
            int estimatedInputTokens, ContextBudget budget, LsmContextPolicySnapshot snapshot) {
        return checkedEstimatedTokens(estimatedInputTokens)
                > requiredSnapshot(snapshot).softTriggerTokens(requiredBudget(budget));
    }

    /** 返回压缩计划应努力达到的 65% 输入水位。 */
    public int targetInputTokens(ContextBudget budget, LsmContextPolicySnapshot snapshot) {
        return requiredSnapshot(snapshot).targetInputTokens(requiredBudget(budget));
    }

    /** 超过硬边界时，模型调用前必须先完成有效压缩。 */
    public boolean exceedsHardInputLimit(int estimatedInputTokens, ContextBudget budget) {
        return checkedEstimatedTokens(estimatedInputTokens)
                > requiredBudget(budget).hardInputLimitTokens();
    }

    private static int checkedEstimatedTokens(int estimatedInputTokens) {
        if (estimatedInputTokens < 0) {
            throw new IllegalArgumentException("estimatedInputTokens must not be negative");
        }
        return estimatedInputTokens;
    }

    private static ContextBudget requiredBudget(ContextBudget budget) {
        return Objects.requireNonNull(budget, "budget");
    }

    private static LsmContextPolicySnapshot requiredSnapshot(LsmContextPolicySnapshot snapshot) {
        return Objects.requireNonNull(snapshot, "snapshot");
    }
}
