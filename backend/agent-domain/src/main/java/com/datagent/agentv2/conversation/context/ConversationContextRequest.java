package com.datagent.agentv2.conversation.context;

import java.util.List;
import java.util.Objects;

/** 上下文策略准备单次模型输入所需的稳定边界和固定框架。 */
public record ConversationContextRequest(
        ContextExecutionRef execution,
        ContextBudget budget,
        ContextPolicySnapshot policy,
        List<ContextMessage> fixedSystemMessages,
        List<ContextToolDefinition> toolDefinitions) {

    public ConversationContextRequest {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(policy, "policy");
        fixedSystemMessages = List.copyOf(fixedSystemMessages);
        toolDefinitions = List.copyOf(toolDefinitions);
    }

    /** 使用 execution 已持久化的策略替换服务当前配置。 */
    public ConversationContextRequest withPolicy(ContextPolicySnapshot replacement) {
        return new ConversationContextRequest(
                execution, budget, replacement, fixedSystemMessages, toolDefinitions);
    }
}
