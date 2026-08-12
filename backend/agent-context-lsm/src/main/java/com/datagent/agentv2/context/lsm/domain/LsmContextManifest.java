package com.datagent.agentv2.context.lsm.domain;

import java.util.List;
import java.util.Objects;

/** 规划层中的已发布 LSM 视图，不依赖持久化实体或 JSON 表示。 */
public record LsmContextManifest(
        /** 当前活动 ROOT 标识；尚未形成 ROOT 时为空。 */
        String activeRootSegmentId,
        /** Storage Frontier 中按时间排序的活动段标识。 */
        List<String> activeSegmentIds,
        /** 等待封存的原始历史起始轮次；没有 pending raw 时为空。 */
        Long pendingRawStartTurnSequence,
        /** 等待封存的原始历史结束轮次；没有 pending raw 时为空。 */
        Long pendingRawEndTurnSequence,
        /** Warm 连续区间起始轮次；历史不足时为空。 */
        Long warmStartTurnSequence,
        /** Warm 连续区间结束轮次；历史不足时为空。 */
        Long warmEndTurnSequence,
        /** 成功发布后单调递增的视图代次。 */
        long generation,
        /** 上次发布视图冻结的 fanout。 */
        int effectiveFanout,
        /** 上次发布视图冻结的 maxLevel。 */
        int effectiveMaxLevel) {

    public LsmContextManifest {
        activeSegmentIds =
                List.copyOf(Objects.requireNonNull(activeSegmentIds, "activeSegmentIds"));
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        validateOptionalRange(
                pendingRawStartTurnSequence, pendingRawEndTurnSequence, "pending raw range");
        validateOptionalRange(warmStartTurnSequence, warmEndTurnSequence, "warm range");
    }

    /** 创建从未发布过压缩结果的空视图。 */
    public static LsmContextManifest empty() {
        return new LsmContextManifest(null, List.of(), null, null, null, null, 0L, 0, 0);
    }

    /** 返回当前 Warm 区间包含的完整问答数量。 */
    public long warmTurnCount() {
        if (warmStartTurnSequence == null) {
            return 0L;
        }
        return warmEndTurnSequence - warmStartTurnSequence + 1L;
    }

    private static void validateOptionalRange(Long start, Long end, String rangeName) {
        if ((start == null) != (end == null)) {
            throw new IllegalArgumentException(
                    rangeName + " boundaries must both be present or absent");
        }
        if (start != null && (start <= 0L || end < start)) {
            throw new IllegalArgumentException(rangeName + " is invalid");
        }
    }
}
