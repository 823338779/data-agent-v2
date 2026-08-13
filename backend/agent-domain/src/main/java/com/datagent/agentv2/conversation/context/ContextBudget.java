package com.datagent.agentv2.conversation.context;

/** 单次模型调用的上下文窗口、输出和安全余量预算。 */
public record ContextBudget(
        int contextWindowTokens,
        int maxOutputTokens,
        int safetyReserveTokens,
        int fixedInputTokens) {

    public ContextBudget {
        if (contextWindowTokens <= 0
                || maxOutputTokens < 0
                || safetyReserveTokens < 0
                || fixedInputTokens < 0
                || maxOutputTokens + safetyReserveTokens >= contextWindowTokens) {
            throw new IllegalArgumentException("Invalid context budget");
        }
    }

    public int hardInputLimitTokens() {
        return contextWindowTokens - maxOutputTokens - safetyReserveTokens;
    }
}
