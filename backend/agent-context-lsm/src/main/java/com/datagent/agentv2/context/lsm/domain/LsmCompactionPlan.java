package com.datagent.agentv2.context.lsm.domain;

import java.util.List;
import java.util.Objects;

/** Planner 的确定性结果，包含候选操作及其成功发布后的完整投影视图。 */
public record LsmCompactionPlan(
        /** 当前轮和最后十个完整问答形成的不可压缩边界。 */
        HotBoundary hotBoundary,
        /** 必须按顺序生成或应用的不可变规划操作。 */
        List<PlannedOperation> operations,
        /** 全部操作成功后要由事务发布的新 manifest。 */
        LsmContextManifest proposedManifest,
        /** 全部操作成功后的 Storage Frontier，供状态机不变量校验。 */
        List<LsmSummarySegment> projectedSegments) {

    public LsmCompactionPlan {
        hotBoundary = Objects.requireNonNull(hotBoundary, "hotBoundary");
        operations = List.copyOf(Objects.requireNonNull(operations, "operations"));
        proposedManifest = Objects.requireNonNull(proposedManifest, "proposedManifest");
        projectedSegments =
                List.copyOf(Objects.requireNonNull(projectedSegments, "projectedSegments"));
    }

    /** 返回所有创建 L0 的操作。 */
    public List<PlannedOperation> seals() {
        return operations.stream()
                .filter(operation -> operation.type == OperationType.SEAL_L0)
                .toList();
    }

    /** 返回所有以现有或计划节点为来源的合并操作。 */
    public List<PlannedOperation> merges() {
        return operations.stream()
                .filter(operation -> operation.type != OperationType.SEAL_L0)
                .filter(operation -> operation.type != OperationType.TRIM_WARM)
                .toList();
    }

    /** 标识 Hot 原文保护范围及当前未完成问答。 */
    public record HotBoundary(
            /** Hot 首个完整问答序号；没有完整问答时为空。 */
            Long startTurnSequence,
            /** Hot 最后一个完整问答序号；没有完整问答时为空。 */
            Long endTurnSequence,
            /** Hot 中受保护完整问答的实际数量，最多为十。 */
            int completedTurnCount,
            /** 当前未完成问答序号；没有当前轮时为空。 */
            Long currentTurnSequence) {

        public HotBoundary {
            if (completedTurnCount < 0 || completedTurnCount > 10) {
                throw new IllegalArgumentException(
                        "completedTurnCount must be between zero and ten");
            }
        }
    }

    /** 一项可串联执行的摘要生成或 manifest 边界操作。 */
    public record PlannedOperation(
            /** 操作的规划类别。 */
            OperationType type,
            /** 候选目标段的确定性标识；纯边界操作可以为空。 */
            String targetId,
            /** 摘要来源段标识，严格按时间顺序排列。 */
            List<String> sourceIds,
            /** 候选目标段种类。 */
            LsmSummarySegment.Kind targetKind,
            /** 候选目标所在逻辑层。 */
            int targetLevel,
            /** 候选目标覆盖的连续完整问答区间。 */
            LsmSummarySegment.Coverage coverage,
            /** forced L0 是否不足正常十轮批次。 */
            boolean partialSegment,
            /** 候选摘要正文的规划 token 估算。 */
            int estimatedTokenCount) {

        public PlannedOperation {
            type = Objects.requireNonNull(type, "type");
            sourceIds = List.copyOf(Objects.requireNonNull(sourceIds, "sourceIds"));
            coverage = Objects.requireNonNull(coverage, "coverage");
            if (targetLevel < 0 || estimatedTokenCount < 0) {
                throw new IllegalArgumentException("operation numeric fields must not be negative");
            }
        }
    }

    /** Planner 严格执行的操作类别。 */
    public enum OperationType {
        SEAL_L0,
        PROMOTE,
        ROLL_ROOT,
        TRIM_WARM,
        MAJOR_COMPACTION,
        RECONCILE_CONFIGURATION
    }
}
