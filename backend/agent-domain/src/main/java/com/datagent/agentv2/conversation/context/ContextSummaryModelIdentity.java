package com.datagent.agentv2.conversation.context;

import java.util.Objects;

/** 摘要模型在生成候选前即可读取的稳定供应商与模型身份，不包含凭据或动态请求参数。 */
public record ContextSummaryModelIdentity(
        /** 摘要模型供应商的稳定标识。 */
        String provider,
        /** 摘要模型名称或部署标识。 */
        String model) {

    public ContextSummaryModelIdentity {
        provider = Objects.requireNonNull(provider, "provider");
        model = Objects.requireNonNull(model, "model");
        if (provider.isBlank() || model.isBlank()) {
            throw new IllegalArgumentException("summary model identity must not be blank");
        }
    }
}
