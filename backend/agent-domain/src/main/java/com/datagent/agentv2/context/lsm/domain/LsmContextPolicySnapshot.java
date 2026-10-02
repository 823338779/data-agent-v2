package com.datagent.agentv2.context.lsm.domain;

import com.datagent.agentv2.conversation.context.ContextBudget;

/** LSM execution 首次使用时冻结的不可变策略参数。 */
public record LsmContextPolicySnapshot(
        /** 持久化策略契约版本。 */
        int policyVersion,
        /** 每层归并时允许聚合的子段数量。 */
        int fanout,
        /** 摘要树允许使用的最高层级。 */
        int maxLevel) {

    private static final int POLICY_VERSION = 1;
    private static final int BASE_SEGMENT_TURNS = 10;
    private static final int COMPRESSION_THRESHOLD_PERCENT = 75;
    private static final int TARGET_WATERMARK_PERCENT = 65;
    private static final int PROTECTED_HOT_TURNS = 10;
    private static final int MIN_FANOUT = 3;
    private static final int MAX_FANOUT = 10;
    private static final int MIN_LEVEL = 1;
    private static final int MAX_LEVEL = 8;

    public LsmContextPolicySnapshot {
        if (policyVersion <= 0
                || fanout < MIN_FANOUT
                || fanout > MAX_FANOUT
                || maxLevel < MIN_LEVEL
                || maxLevel > MAX_LEVEL) {
            throw new IllegalArgumentException("Invalid context policy snapshot");
        }
    }

    /** 以服务级 fanout 和 max level 生成保持历史兼容的默认快照。 */
    public static LsmContextPolicySnapshot defaults(int fanout, int maxLevel) {
        return new LsmContextPolicySnapshot(POLICY_VERSION, fanout, maxLevel);
    }

    /** 返回压缩触发使用的完整窗口软水位。 */
    public int softTriggerTokens(ContextBudget budget) {
        return Math.floorDiv(budget.contextWindowTokens() * COMPRESSION_THRESHOLD_PERCENT, 100);
    }

    /** 返回压缩后应努力达到的完整窗口目标水位。 */
    public int targetInputTokens(ContextBudget budget) {
        return Math.floorDiv(budget.contextWindowTokens() * TARGET_WATERMARK_PERCENT, 100);
    }

    /** 返回算法固定的叶段轮数，不作为逐快照重复字段持久化。 */
    public int baseSegmentTurns() {
        return BASE_SEGMENT_TURNS;
    }

    /** 返回算法固定的压缩触发百分比。 */
    public int compressionThresholdPercent() {
        return COMPRESSION_THRESHOLD_PERCENT;
    }

    /** 返回算法固定的目标水位百分比。 */
    public int targetWatermarkPercent() {
        return TARGET_WATERMARK_PERCENT;
    }

    /** 返回算法固定的 Hot 区保护轮数。 */
    public int protectedHotTurns() {
        return PROTECTED_HOT_TURNS;
    }
}
