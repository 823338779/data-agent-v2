package com.datagent.agentv2.memory.review;

/**
 * 一次后台批次已连续判断的结果；暂时故障或未终态消息之前必须停止推进。
 *
 * @param reviewedThroughSequence 连续已处理的包含上界，必须位于领取的冻结范围内
 * @param rejectedOperations 已判断但因安全、配额、保护或抑制而未保存的操作数量，不含暂时失败
 */
public record MemoryReviewProgress(long reviewedThroughSequence, int rejectedOperations) {
    public MemoryReviewProgress {
        if (reviewedThroughSequence < 0 || rejectedOperations < 0)
            throw new IllegalArgumentException("审查进度与拒绝数量不得为负数");
    }
}
