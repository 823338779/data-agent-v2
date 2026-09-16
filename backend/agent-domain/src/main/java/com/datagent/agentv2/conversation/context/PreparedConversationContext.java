package com.datagent.agentv2.conversation.context;

import java.util.List;
import java.util.Objects;

/** 上下文策略为模型调用产生的不可变输入快照。 */
public record PreparedConversationContext(
        String strategyId,
        List<ContextMessage> messages,
        int estimatedInputTokens,
        boolean compacted,
        /** 当前策略正式发布状态的中立代次；raw 固定为 0。 */
        long strategyGeneration,
        RuntimeContextStatistics runtimeStatistics) {

    /** 旧调用方没有运行时贡献，统计保持零值。 */
    public PreparedConversationContext(
            String strategyId,
            List<ContextMessage> messages,
            int estimatedInputTokens,
            boolean compacted,
            long strategyGeneration) {
        this(
                strategyId,
                messages,
                estimatedInputTokens,
                compacted,
                strategyGeneration,
                RuntimeContextStatistics.ZERO);
    }

    public PreparedConversationContext {
        Objects.requireNonNull(strategyId, "strategyId");
        messages = List.copyOf(messages);
        Objects.requireNonNull(runtimeStatistics, "runtimeStatistics");
        if (estimatedInputTokens < 0 || strategyGeneration < 0) {
            throw new IllegalArgumentException("Context counters must not be negative");
        }
    }
}
