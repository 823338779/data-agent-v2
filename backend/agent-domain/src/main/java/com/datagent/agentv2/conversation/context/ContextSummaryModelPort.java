package com.datagent.agentv2.conversation.context;

/** 上下文策略访问摘要模型的供应商无关端口。 */
public interface ContextSummaryModelPort {

    ContextSummaryResult summarize(ContextSummaryRequest request);
}
