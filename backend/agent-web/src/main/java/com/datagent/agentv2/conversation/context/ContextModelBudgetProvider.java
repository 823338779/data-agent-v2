package com.datagent.agentv2.conversation.context;

import com.datagent.agentv2.conversation.model.OllamaModelProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 按当前模型 provider 返回一次调用使用的确定性上下文预算。 */
@Component
public class ContextModelBudgetProvider {

    private static final String PROVIDER_OLLAMA = "ollama";

    private final String provider;
    private final OllamaModelProperties ollama;
    private final ContextModelInputProperties generic;

    public ContextModelBudgetProvider(
            @Value("${data-agent.model.provider:ollama}") String provider,
            OllamaModelProperties ollama,
            ContextModelInputProperties generic) {
        this.provider = provider;
        this.ollama = ollama;
        this.generic = generic;
    }

    public ContextBudget currentBudget() {
        // Ollama 的请求选项是事实源；其他 provider 使用显式统一服务预算。
        if (PROVIDER_OLLAMA.equals(provider)) {
            return new ContextBudget(
                    ollama.getContextWindowTokens(),
                    ollama.getMaxOutputTokens(),
                    ollama.getSafetyReserveTokens(),
                    0);
        }
        return new ContextBudget(
                generic.getContextWindowTokens(),
                generic.getMaxOutputTokens(),
                generic.getSafetyReserveTokens(),
                generic.getFixedProtocolTokens());
    }
}
