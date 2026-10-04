package com.datagent.agentv2.context.hierarchical.planning;

import com.datagent.agentv2.context.hierarchical.configuration.StepCompactionProperties;
import com.datagent.agentv2.context.hierarchical.domain.ContextStepCursor;
import com.datagent.agentv2.context.hierarchical.domain.ContextStepGroup;
import com.datagent.agentv2.context.hierarchical.runtime.HierarchicalContextWindowExceededException;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** 一次 prepare 只规划一次完整前缀；保留近期组不得挤占摘要的必要空间。 */
@RequiredArgsConstructor
public final class HierarchicalStepBudgetPlanner {
    private static final int PREFERRED_SUMMARY_TOKENS = 256;
    private final ConversationTokenEstimator estimator;
    private final StepCompactionProperties properties;

    public Plan plan(
            List<ContextStepGroup> groups,
            ContextStepCursor covered,
            int hardBudget,
            int necessaryTokens) {
        properties.validate();
        if (necessaryTokens > hardBudget) {
            throw new HierarchicalContextWindowExceededException();
        }
        int eligible = 0;
        int alreadyCovered = 0;
        for (var group : groups) {
            if (!group.complete()) {
                break;
            }
            eligible++;
            if (group.throughInclusive().compareTo(covered) <= 0) {
                alreadyCovered++;
            }
        }
        int cut = Math.max(alreadyCovered, eligible - properties.getRetainedCompletedGroups());
        int target = (int) Math.floor(hardBudget * properties.getTargetRatio());
        // 从软保留开始释放完整组，不得跨过任何未终态工具组。
        while (cut < eligible
                && available(groups, cut, target, necessaryTokens) < PREFERRED_SUMMARY_TOKENS) {
            cut++;
        }
        int summary = available(groups, cut, target, necessaryTokens);
        if (summary <= 0) {
            summary = available(groups, cut, hardBudget, necessaryTokens);
        }
        if (summary <= 0 || cut == 0) {
            return new Plan(List.of(), groups, 0);
        }
        return new Plan(groups.subList(0, cut), groups.subList(cut, groups.size()), summary);
    }

    private int available(List<ContextStepGroup> groups, int cut, int total, int necessary) {
        long suffix =
                groups.subList(cut, groups.size()).stream()
                        .mapToLong(group -> estimator.estimateMessages(group.messages()))
                        .sum();
        return (int) Math.max(Integer.MIN_VALUE, total - (long) necessary - suffix);
    }

    /** 摘要只消费 prefix；suffix 包含全部未完成协议，不得另行截断。 */
    public record Plan(
            /** 从历史起点连续覆盖的完整组，包含上一版已覆盖前缀。 */
            List<ContextStepGroup> prefix,
            /** 未覆盖的原文/有界投影组。 */
            List<ContextStepGroup> suffix,
            /** 包装开销已由 necessaryTokens 预留后的摘要正文上限。 */
            int summaryBudget) {
        public Plan {
            prefix = List.copyOf(prefix);
            suffix = List.copyOf(suffix);
        }
    }
}
