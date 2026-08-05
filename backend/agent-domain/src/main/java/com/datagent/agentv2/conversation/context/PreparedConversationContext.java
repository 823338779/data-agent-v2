package com.datagent.agentv2.conversation.context;

import java.util.List;
import java.util.Objects;

/** 上下文策略为模型调用产生的不可变输入快照。 */
public record PreparedConversationContext(
        String strategyId,
        List<ContextMessage> messages,
        int estimatedInputTokens,
        boolean compacted,
        long manifestGeneration) {

    public PreparedConversationContext {
        Objects.requireNonNull(strategyId, "strategyId");
        messages = List.copyOf(messages);
        if (estimatedInputTokens < 0 || manifestGeneration < 0) {
            throw new IllegalArgumentException("Context counters must not be negative");
        }
    }
}
