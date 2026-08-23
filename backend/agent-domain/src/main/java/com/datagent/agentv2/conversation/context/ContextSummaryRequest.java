package com.datagent.agentv2.conversation.context;

import java.util.Objects;

/** 一次不携带工具、不会产生用户可见增量的摘要模型请求。 */
public record ContextSummaryRequest(
        String systemPrompt,
        String sourceDocument,
        String outputSchema,
        /** 确定性紧凑渲染结果允许占用的 token 上限。 */
        int targetRenderedTokens,
        /** provider 生成完整 JSON 时允许使用的输出 token 上限。 */
        int maxOutputTokens,
        /** 摘要 provider 单次请求允许使用的严格输入 token 上限。 */
        int inputHardTokenBudget) {

    public ContextSummaryRequest {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(sourceDocument, "sourceDocument");
        Objects.requireNonNull(outputSchema, "outputSchema");
        if (targetRenderedTokens <= 0 || maxOutputTokens <= 0 || inputHardTokenBudget <= 0) {
            throw new IllegalArgumentException("Summary budgets must be positive");
        }
    }
}
