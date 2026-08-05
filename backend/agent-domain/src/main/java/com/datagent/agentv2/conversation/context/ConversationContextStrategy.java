package com.datagent.agentv2.conversation.context;

/** 为一次模型调用准备有界会话上下文的可替换策略。 */
public interface ConversationContextStrategy {

    String strategyId();

    PreparedConversationContext prepare(ConversationContextRequest request);
}
