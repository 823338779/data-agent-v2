package com.datagent.agentv2.conversation.context;

import java.util.Objects;

/** 摘要模型完成后返回的正文和安全元数据。 */
public record ContextSummaryResult(
        String content, int estimatedTokenCount, String modelProvider, String modelName) {

    public ContextSummaryResult {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(modelProvider, "modelProvider");
        Objects.requireNonNull(modelName, "modelName");
        if (estimatedTokenCount < 0) {
            throw new IllegalArgumentException("Summary token count must not be negative");
        }
    }
}
