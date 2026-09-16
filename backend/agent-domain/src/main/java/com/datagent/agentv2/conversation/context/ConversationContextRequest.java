package com.datagent.agentv2.conversation.context;

import java.util.List;
import java.util.Objects;

/** 上下文策略准备单次模型输入所需的稳定边界和固定框架。 */
public record ConversationContextRequest(
        ContextExecutionRef execution,
        ContextBudget budget,
        List<ContextMessage> fixedSystemMessages,
        List<ContextToolDefinition> toolDefinitions,
        RuntimeContextInput runtimeInput) {

    public ConversationContextRequest {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(budget, "budget");
        fixedSystemMessages = List.copyOf(fixedSystemMessages);
        toolDefinitions = List.copyOf(toolDefinitions);
        runtimeInput = Objects.requireNonNull(runtimeInput, "runtimeInput");
    }

    /** 兼容不提供运行时输入的既有调用方。 */
    public ConversationContextRequest(
            ContextExecutionRef execution,
            ContextBudget budget,
            List<ContextMessage> fixedSystemMessages,
            List<ContextToolDefinition> toolDefinitions) {
        this(execution, budget, fixedSystemMessages, toolDefinitions, RuntimeContextInput.empty());
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

    /** 从 invocation 固定协议与已读取的运行时输入组装策略请求。 */
    public static ConversationContextRequest composeForInvocation(
            ContextExecutionRef execution,
            ContextBudget budget,
            List<ContextMessage> fixedSystemMessages,
            List<ContextToolDefinition> toolDefinitions,
            RuntimeContextInput runtimeInput) {
        return new ConversationContextRequest(
                execution, budget, fixedSystemMessages, toolDefinitions, runtimeInput);
    }
}
