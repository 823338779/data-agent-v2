package com.datagent.agentv2.context.hierarchical.domain;

import com.datagent.agentv2.context.common.summary.EventSummaryCoverage;
import java.util.Objects;

/**
 * 一次完整可审计、可由 checkpoint 原子发布的滚动 Cold 摘要。
 *
 * @param coldId 由会话、摘要内容和模型身份确定的不可变标识
 * @param conversationSessionId 摘要所属会话
 * @param coverage 原始轮次与消息的闭区间覆盖，两者共同定位来源
 * @param summaryDocument 校验后的结构化摘要 JSON，不可修改
 * @param persistedTokenCount 持久化 JSON 的估算 token 数
 * @param renderedTokenCount 注入上下文的渲染形态 token 数，不超过 targetRenderedTokens
 * @param targetRenderedTokens 本次生成允许的渲染 token 上限
 * @param modelProvider 生成模型的供应商标识
 * @param modelName 生成时的模型名称
 * @param sourceContentSha256 直接来源正文的 SHA-256 指纹，用于追溯
 */
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
