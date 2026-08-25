package com.datagent.agentv2.context.hierarchical.domain;

import com.datagent.agentv2.context.common.summary.EventSummaryCoverage;
import java.util.Objects;

/** 一次完整可审计、可由 checkpoint 原子发布的滚动 Cold 摘要。 */
public record HierarchicalCold(
        String coldId,
        String conversationSessionId,
        EventSummaryCoverage coverage,
        String summaryDocument,
        int persistedTokenCount,
        int renderedTokenCount,
        int targetRenderedTokens,
        String modelProvider,
        String modelName,
        String sourceContentSha256) {

    public HierarchicalCold {
        requireText(coldId, "coldId");
        requireText(conversationSessionId, "conversationSessionId");
        coverage = Objects.requireNonNull(coverage, "coverage");
        requireText(summaryDocument, "summaryDocument");
        requireText(modelProvider, "modelProvider");
        requireText(modelName, "modelName");
        requireText(sourceContentSha256, "sourceContentSha256");
        if (persistedTokenCount <= 0
                || renderedTokenCount <= 0
                || targetRenderedTokens <= 0
                || renderedTokenCount > targetRenderedTokens) {
            throw new IllegalArgumentException("Invalid hierarchical Cold");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
