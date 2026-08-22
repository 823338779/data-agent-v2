package com.datagent.agentv2.conversation.context;

import java.util.List;
import java.util.Objects;

/** 上下文策略准备单次模型输入所需的稳定边界和固定框架。 */
public record ConversationContextRequest(
        ContextExecutionRef execution,
        ContextBudget budget,
        List<ContextMessage> fixedSystemMessages,
        List<ContextToolDefinition> toolDefinitions) {

    public ConversationContextRequest {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(budget, "budget");
        fixedSystemMessages = List.copyOf(fixedSystemMessages);
        toolDefinitions = List.copyOf(toolDefinitions);
    }

    /** 从一次 invocation 的执行边界、预算和固定协议数据组装策略请求。 */
    public static ConversationContextRequest composeForInvocation(
            ContextExecutionRef execution,
            ContextBudget budget,
            List<ContextMessage> fixedSystemMessages,
            List<ContextToolDefinition> toolDefinitions) {
        return new ConversationContextRequest(
                execution, budget, fixedSystemMessages, toolDefinitions);
    }
}
