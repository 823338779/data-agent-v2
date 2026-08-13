package com.datagent.agentv2.context.hierarchical.domain;

import java.util.List;
import java.util.Objects;

/** 一次 Warm 输入使用的有序二叉 frontier 及缺失覆盖和预算结果。 */
public record FrontierSelection(
        /** 按时间升序排列的不可变摘要节点标识。 */
        List<String> nodeIds,
        /** 与节点标识一一对应的二进制覆盖范围。 */
        List<SummaryRange> ranges,
        /** 当前节点库无法连续覆盖、需要后续生成的对齐范围。 */
        List<SummaryRange> missingRanges,
        /** 所选摘要节点的 token 估算总数。 */
        int estimatedTokenCount,
        /** 最粗合法表示是否位于本次 Warm 预算内。 */
        boolean withinBudget,
        /** 目标 Warm 内部是否存在可通过合法二叉节点达到预算的结构路径。 */
        boolean satisfiable) {

    /** 固化所有列表副本，避免规划结果被调用方修改。 */
    public FrontierSelection {
        nodeIds = List.copyOf(Objects.requireNonNull(nodeIds, "nodeIds"));
        ranges = List.copyOf(Objects.requireNonNull(ranges, "ranges"));
        missingRanges = List.copyOf(Objects.requireNonNull(missingRanges, "missingRanges"));
        boolean complete = missingRanges.isEmpty();
        if (nodeIds.size() != ranges.size()
                || estimatedTokenCount < 0
                || (!satisfiable && (withinBudget || !complete))
                || (satisfiable && complete && !withinBudget)) {
            throw new IllegalArgumentException("Invalid frontier selection");
        }
    }

    /** 返回当前结果能否安全发布为完整 Warm frontier。 */
    public boolean publishable() {
        return satisfiable && missingRanges.isEmpty() && withinBudget;
    }

    /** 返回不包含任何 Warm 节点的初始规划结果。 */
    public static FrontierSelection empty() {
        return new FrontierSelection(List.of(), List.of(), List.of(), 0, true, true);
    }
}
