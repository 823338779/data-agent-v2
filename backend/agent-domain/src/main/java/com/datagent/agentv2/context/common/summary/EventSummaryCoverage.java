package com.datagent.agentv2.context.common.summary;

/** 摘要生成器必须原样覆盖的可信轮次与消息闭区间。 */
public record EventSummaryCoverage(
        long startTurnSequence,
        long endTurnSequence,
        long startMessageSequence,
        long endMessageSequence) {

    public EventSummaryCoverage {
        if (startTurnSequence <= 0
                || endTurnSequence < startTurnSequence
                || startMessageSequence <= 0
                || endMessageSequence < startMessageSequence) {
            throw new IllegalArgumentException("Event summary coverage is invalid");
        }
    }
}
