package com.datagent.agentv2.context.lsm.domain;

import com.datagent.agentv2.conversation.context.ContextBudget;

/** LSM execution 首次使用时冻结的不可变策略参数。 */
public record LsmContextPolicySnapshot(
        int policyVersion,
        int fanout,
        int maxLevel,
        int baseSegmentTurns,
        int compressionThresholdPercent,
        int targetWatermarkPercent,
        int warmTargetTurns,
        int warmMaxTurns,
        int protectedHotTurns) {

    private static final int POLICY_VERSION = 1;
    private static final int BASE_SEGMENT_TURNS = 10;
    private static final int COMPRESSION_THRESHOLD_PERCENT = 75;
    private static final int TARGET_WATERMARK_PERCENT = 65;
    private static final int WARM_TARGET_TURNS = 100;
    private static final int WARM_MAX_TURNS = 109;
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
                || maxLevel > MAX_LEVEL
                || baseSegmentTurns <= 0
                || compressionThresholdPercent <= targetWatermarkPercent
                || compressionThresholdPercent >= 100
                || targetWatermarkPercent <= 0
                || warmTargetTurns <= 0
                || warmMaxTurns < warmTargetTurns
                || protectedHotTurns <= 0) {
            throw new IllegalArgumentException("Invalid context policy snapshot");
        }
    }

    /** 以服务级 fanout 和 max level 生成保持历史兼容的默认快照。 */
    public static LsmContextPolicySnapshot defaults(int fanout, int maxLevel) {
        return new LsmContextPolicySnapshot(
                POLICY_VERSION,
                fanout,
                maxLevel,
                BASE_SEGMENT_TURNS,
                COMPRESSION_THRESHOLD_PERCENT,
                TARGET_WATERMARK_PERCENT,
                WARM_TARGET_TURNS,
                WARM_MAX_TURNS,
                PROTECTED_HOT_TURNS);
    }

    /** 返回压缩触发使用的完整窗口软水位。 */
    public int softTriggerTokens(ContextBudget budget) {
        return Math.floorDiv(budget.contextWindowTokens() * compressionThresholdPercent, 100);
    }

    /** 返回压缩后应努力达到的完整窗口目标水位。 */
    public int targetInputTokens(ContextBudget budget) {
        return Math.floorDiv(budget.contextWindowTokens() * targetWatermarkPercent, 100);
    }
}
