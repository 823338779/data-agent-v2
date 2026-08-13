package com.datagent.agentv2.context.hierarchical.domain;

import java.util.Objects;

/** 会话当前正式发布的 Cold 摘要、Warm frontier 和单调指针。 */
public record HierarchicalCheckpoint(
        String checkpointId,
        String conversationSessionId,
        String coldSummaryDocument,
        long summarizedUpToConversationMessageSequence,
        long summarizedCompletedTurnCount,
        int estimatedTokenCount,
        int summarySchemaVersion,
        int summaryPromptVersion,
        String modelProvider,
        String modelName,
        String activeFrontierDocument,
        long strategyGeneration,
        long version) {

    /** 在进入持久化边界前拒绝不完整或倒退到负数的检查点状态。 */
    public HierarchicalCheckpoint {
        Objects.requireNonNull(checkpointId, "checkpointId");
        Objects.requireNonNull(conversationSessionId, "conversationSessionId");
        Objects.requireNonNull(coldSummaryDocument, "coldSummaryDocument");
        Objects.requireNonNull(modelProvider, "modelProvider");
        Objects.requireNonNull(modelName, "modelName");
        Objects.requireNonNull(activeFrontierDocument, "activeFrontierDocument");
        if (summarizedUpToConversationMessageSequence < 0
                || summarizedCompletedTurnCount < 0
                || estimatedTokenCount < 0
                || summarySchemaVersion <= 0
                || summaryPromptVersion <= 0
                || strategyGeneration < 0
                || version < 0) {
            throw new IllegalArgumentException("Invalid hierarchical checkpoint");
        }
    }
}
