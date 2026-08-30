package com.datagent.agentv2.conversation.tool.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 按服务端配置选择搜索 Provider，模型不能控制供应商。 */
@Service
public final class WebSearchProviderRegistry {

    private final Map<String, WebSearchProvider> providers;

    public WebSearchProviderRegistry(List<WebSearchProvider> registeredProviders) {
        Map<String, WebSearchProvider> indexed = new LinkedHashMap<>();
        for (WebSearchProvider provider : registeredProviders) {
            if (indexed.put(provider.id(), provider) != null) {
                throw new IllegalStateException("Duplicate web search provider: " + provider.id());
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    public WebSearchProvider require(String providerId) {
        WebSearchProvider provider = providers.get(providerId);
        if (provider == null) {
            throw new IllegalStateException("Unknown web search provider: " + providerId);
        }
        return provider;
    }
}
