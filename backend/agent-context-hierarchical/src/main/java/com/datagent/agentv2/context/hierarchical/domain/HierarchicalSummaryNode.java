package com.datagent.agentv2.context.hierarchical.domain;

import java.util.Objects;

/** Warm 层中由覆盖范围和生成版本确定身份的不可变二进制摘要节点。 */
public record HierarchicalSummaryNode(
        String nodeId,
        String conversationSessionId,
        int level,
        long coveredCompletedTurnCount,
        long startConversationMessageSequence,
        long endConversationMessageSequence,
        String leftChildNodeId,
        String rightChildNodeId,
        String summaryDocument,
        int estimatedTokenCount,
        int summarySchemaVersion,
        int summaryPromptVersion,
        String modelProvider,
        String modelName) {

    /** 保证叶节点和父节点的基本形状可由数据库约束进一步验证。 */
    public HierarchicalSummaryNode {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(conversationSessionId, "conversationSessionId");
        Objects.requireNonNull(summaryDocument, "summaryDocument");
        Objects.requireNonNull(modelProvider, "modelProvider");
        Objects.requireNonNull(modelName, "modelName");
        boolean leaf = level == 0 && leftChildNodeId == null && rightChildNodeId == null;
        boolean parent = level > 0 && leftChildNodeId != null && rightChildNodeId != null;
        if (level < 0
                || coveredCompletedTurnCount <= 0
                || startConversationMessageSequence < 0
                || startConversationMessageSequence > endConversationMessageSequence
                || estimatedTokenCount < 0
                || summarySchemaVersion <= 0
                || summaryPromptVersion <= 0
                || (!leaf && !parent)) {
            throw new IllegalArgumentException("Invalid hierarchical summary node");
        }
    }
}
