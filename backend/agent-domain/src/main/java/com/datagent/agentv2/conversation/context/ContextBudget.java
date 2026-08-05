package com.datagent.agentv2.conversation.context;

/** 单次模型调用的上下文窗口、输出和安全余量预算。 */
public record ContextBudget(
        int contextWindowTokens,
        int maxOutputTokens,
        int safetyReserveTokens,
        int fixedInputTokens) {

    private static final double SOFT_TRIGGER_RATIO = 0.75d;
    private static final double TARGET_INPUT_RATIO = 0.65d;

    public ContextBudget {
        if (contextWindowTokens <= 0
                || maxOutputTokens < 0
                || safetyReserveTokens < 0
                || fixedInputTokens < 0
                || maxOutputTokens + safetyReserveTokens >= contextWindowTokens) {
            throw new IllegalArgumentException("Invalid context budget");
        }
    }

    public int softTriggerTokens() {
        return (int) Math.floor(contextWindowTokens * SOFT_TRIGGER_RATIO);
    }

    public int targetInputTokens() {
        return (int) Math.floor(contextWindowTokens * TARGET_INPUT_RATIO);
    }

    public int hardInputLimitTokens() {
        return contextWindowTokens - maxOutputTokens - safetyReserveTokens;
    }
}
