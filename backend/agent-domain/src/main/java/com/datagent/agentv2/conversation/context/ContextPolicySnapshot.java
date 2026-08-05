package com.datagent.agentv2.conversation.context;

/** Execution 首次使用上下文策略时固化的不可变策略参数。 */
public record ContextPolicySnapshot(
        int policyVersion,
        int fanout,
        int maxLevel,
        int baseSegmentTurns,
        int compressionThresholdPercent,
        int targetWatermarkPercent,
        int warmTargetTurns,
        int warmMaxTurns,
        int protectedHotTurns) {

    private static final int MIN_FANOUT = 3;
    private static final int MAX_FANOUT = 10;
    private static final int MIN_LEVEL = 1;
    private static final int MAX_LEVEL = 8;

    public ContextPolicySnapshot {
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
}
