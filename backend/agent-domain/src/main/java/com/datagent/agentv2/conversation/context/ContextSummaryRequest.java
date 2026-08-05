package com.datagent.agentv2.conversation.context;

import java.util.Objects;

/** 一次不携带工具、不会产生用户可见增量的摘要模型请求。 */
public record ContextSummaryRequest(
        String systemPrompt, String sourceDocument, String outputSchema, int maxOutputTokens) {

    public ContextSummaryRequest {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(sourceDocument, "sourceDocument");
        Objects.requireNonNull(outputSchema, "outputSchema");
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("Summary output budget must be positive");
        }
    }
}
