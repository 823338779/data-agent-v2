package com.datagent.agentv2.conversation.context;

/** 上下文策略访问摘要模型的供应商无关端口。 */
public interface ContextSummaryModelPort {

    String IDENTITY_NOT_CONFIGURED = "Context summary model identity is not configured";

    /** 在调用模型前暴露不含凭据的稳定生成身份，供候选幂等键与恢复校验使用。 */
    default ContextSummaryModelIdentity identity() {
        throw new IllegalStateException(IDENTITY_NOT_CONFIGURED);
    }

    ContextSummaryResult summarize(ContextSummaryRequest request);
}
