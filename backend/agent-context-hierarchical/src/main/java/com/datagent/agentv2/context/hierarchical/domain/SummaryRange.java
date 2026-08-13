package com.datagent.agentv2.context.hierarchical.domain;

/** 一个与完整问答序号对齐的不可拆分二进制摘要范围。 */
public record SummaryRange(
        /** 范围内最早完整问答的连续序号。 */
        long startTurn,
        /** 范围内最晚完整问答的连续序号。 */
        long endTurn,
        /** 最早完整问答的首条持久化消息序号。 */
        long startMessageSequence,
        /** 最晚完整问答的末条持久化消息序号。 */
        long endMessageSequence,
        /** 二叉树层级，覆盖问答数固定为 2 的该层级次方。 */
        int level) {

    /** 在进入规划器前拒绝未对齐、非二进制或倒退的范围。 */
    public SummaryRange {
        if (startTurn <= 0L
                || endTurn < startTurn
                || startMessageSequence <= 0L
                || endMessageSequence < startMessageSequence
                || level < 0
                || level > 62) {
            throw new IllegalArgumentException("Invalid summary range");
        }
        long expectedCoverage = 1L << level;
        long actualCoverage = endTurn - startTurn + 1L;
        if (actualCoverage != expectedCoverage || (startTurn - 1L) % expectedCoverage != 0L) {
            throw new IllegalArgumentException("Summary range must be aligned to its binary level");
        }
    }

    /** 返回该节点完整覆盖的问答数量。 */
    public long coveredTurns() {
        return endTurn - startTurn + 1L;
    }

    /** 判断另一个节点是否为当前节点覆盖范围内的严格子孙。 */
    public boolean strictlyContains(SummaryRange other) {
        return startTurn <= other.startTurn
                && endTurn >= other.endTurn
                && (startTurn != other.startTurn || endTurn != other.endTurn);
    }
}
