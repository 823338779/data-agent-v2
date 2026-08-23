package com.datagent.agentv2.context.lsm.domain;

import java.util.List;
import java.util.Objects;

/** 固定一次 compaction 输入快照及其 execution 策略，供 planner 纯函数计算。 */
public record LsmPlanningInput(
        /** 已按问答边界聚合的输入快照，可能包含最后一个未完成问答。 */
        List<ConversationTurn> turns,
        /** 本次计划读取的已发布 manifest。 */
        LsmContextManifest manifest,
        /** manifest 活动段及 frontier 展开可用的不可变摘要段。 */
        List<LsmSummarySegment> segments,
        /** execution 首次创建时冻结的层级策略。 */
        LsmContextPolicySnapshot policy,
        /** 本次完整输入和目标水位的 token 压力快照。 */
        TokenPressure pressure,
        /** 扣除固定输入、最近十轮、当前轮和摘要 wrapper 后可用于摘要渲染的预算。 */
        int historyMemoryBudget,
        /** 超过硬边界后是否允许将不足十轮的完整历史强制封为 L0。 */
        boolean forcePartialL0) {

    public LsmPlanningInput {
        turns = List.copyOf(Objects.requireNonNull(turns, "turns"));
        manifest = Objects.requireNonNull(manifest, "manifest");
        segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        policy = Objects.requireNonNull(policy, "policy");
        pressure = Objects.requireNonNull(pressure, "pressure");
        if (historyMemoryBudget <= 0) {
            throw new IllegalArgumentException("historyMemoryBudget must be positive");
        }
    }

    public LsmPlanningInput(
            List<ConversationTurn> turns,
            LsmContextManifest manifest,
            List<LsmSummarySegment> segments,
            LsmContextPolicySnapshot policy,
            TokenPressure pressure,
            boolean forcePartialL0) {
        this(
                turns,
                manifest,
                segments,
                policy,
                pressure,
                Math.max(1, pressure.hardInputLimitTokens()),
                forcePartialL0);
    }

    /** Planner 判断 forced L0 和 Major Compaction 所需的 token 压力。 */
    public record TokenPressure(
            /** 当前完整模型输入的估算 token。 */
            int estimatedInputTokens,
            /** 成功压缩后努力达到的输入目标水位。 */
            int targetInputTokens,
            /** 正常模型调用不可超过的输入硬边界。 */
            int hardInputLimitTokens) {

        public TokenPressure {
            if (estimatedInputTokens < 0 || targetInputTokens < 0 || hardInputLimitTokens <= 0) {
                throw new IllegalArgumentException("token pressure values are invalid");
            }
            if (targetInputTokens > hardInputLimitTokens) {
                throw new IllegalArgumentException("targetInputTokens must not exceed hard limit");
            }
        }

        /** 返回当前输入是否已经超过目标水位。 */
        public boolean aboveTarget() {
            return estimatedInputTokens > targetInputTokens;
        }

        /** 返回当前输入是否已经超过硬边界。 */
        public boolean aboveHardLimit() {
            return estimatedInputTokens > hardInputLimitTokens;
        }
    }
}
