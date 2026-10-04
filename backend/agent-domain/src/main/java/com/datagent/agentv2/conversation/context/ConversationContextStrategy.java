package com.datagent.agentv2.conversation.context;

/** 为一次模型调用准备有界会话上下文的可替换策略。 */
public interface ConversationContextStrategy {

    String strategyId();

    /** 清理一次执行的临时失败指纹；不删除已发布记忆。 */
    default void closeExecution(String executionId) {}

    PreparedConversationContext prepare(ConversationContextRequest request);
}
