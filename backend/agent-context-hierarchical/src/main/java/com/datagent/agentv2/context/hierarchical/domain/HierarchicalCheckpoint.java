package com.datagent.agentv2.context.hierarchical.domain;

import java.util.Objects;

/** 会话当前原子发布的滚动 Cold 引用与单调边界。 */
public record HierarchicalCheckpoint(
        String checkpointId,
        String conversationSessionId,
        String activeColdId,
        long evaluatedUpToConversationMessageSequence,
        long strategyGeneration,
        long version) {
    public HierarchicalCheckpoint {
        Objects.requireNonNull(checkpointId, "checkpointId");
        Objects.requireNonNull(conversationSessionId, "conversationSessionId");
        if (activeColdId != null && activeColdId.isBlank()) {
            throw new IllegalArgumentException("activeColdId must be null or non-blank");
        }
        if (evaluatedUpToConversationMessageSequence < 0 || strategyGeneration < 0 || version < 0) {
            throw new IllegalArgumentException("Invalid hierarchical checkpoint");
        }
    }
}
