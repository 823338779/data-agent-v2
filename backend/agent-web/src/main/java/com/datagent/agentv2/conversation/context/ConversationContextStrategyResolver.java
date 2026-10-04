package com.datagent.agentv2.conversation.context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 按服务配置解析唯一的上下文策略，并作为调用方的稳定入口。 */
@Component
public class ConversationContextStrategyResolver {

    private static final String ERROR_DUPLICATE_STRATEGY = "Duplicate context strategy id: ";
    private static final String ERROR_UNKNOWN_STRATEGY = "Unknown context strategy id: ";

    @lombok.Getter
    @lombok.experimental.Accessors(fluent = true)
    private final ConversationContextStrategy selected;

    public ConversationContextStrategyResolver(
            List<ConversationContextStrategy> strategies, ContextStrategyProperties properties) {
        Map<String, ConversationContextStrategy> strategiesById = new LinkedHashMap<>();
        for (ConversationContextStrategy strategy : strategies) {
            ConversationContextStrategy previous =
                    strategiesById.putIfAbsent(strategy.strategyId(), strategy);
            if (previous != null) {
                throw new IllegalStateException(ERROR_DUPLICATE_STRATEGY + strategy.strategyId());
            }
        }
        selected = strategiesById.get(properties.getStrategy());
        if (selected == null) {
            throw new IllegalStateException(ERROR_UNKNOWN_STRATEGY + properties.getStrategy());
        }
    }

    public void closeExecution(String executionId) {
        selected.closeExecution(executionId);
    }

    public PreparedConversationContext prepare(ConversationContextRequest request) {
        return selected.prepare(request);
    }
}
