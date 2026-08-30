package com.datagent.agentv2.conversation.tool.web;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 服务端联网搜索配置；API Key 仅供 Provider 使用，字符串表示始终脱敏。 */
@ConfigurationProperties("data-agent.tool.web-search")
public final class WebSearchProperties {

    private final String provider;
    private final String apiKey;
    private final int defaultLimit;
    private final SearchDepth defaultDepth;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Duration toolTimeout;
    private final String proxy;

    public WebSearchProperties(
            @DefaultValue("tavily") String provider,
            @DefaultValue("") String apiKey,
            @DefaultValue("5") int defaultLimit,
            @DefaultValue("basic") SearchDepth defaultDepth,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("20s") Duration requestTimeout,
            @DefaultValue("25s") Duration toolTimeout,
            @DefaultValue("") String proxy) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.defaultLimit = defaultLimit;
        this.defaultDepth = Objects.requireNonNull(defaultDepth, "defaultDepth");
        this.connectTimeout = positive(connectTimeout, "connectTimeout");
        this.requestTimeout = positive(requestTimeout, "requestTimeout");
        this.toolTimeout = positive(toolTimeout, "toolTimeout");
        this.proxy = Objects.requireNonNull(proxy, "proxy").trim();
        if (!"tavily".equals(provider)) {
            throw new IllegalArgumentException("provider must be tavily");
        }
        if (defaultLimit < 1 || defaultLimit > 20) {
            throw new IllegalArgumentException("defaultLimit must be between 1 and 20");
        }
        if (connectTimeout.compareTo(requestTimeout) > 0
                || requestTimeout.compareTo(toolTimeout) >= 0) {
            throw new IllegalArgumentException(
                    "timeouts must satisfy connectTimeout <= requestTimeout < toolTimeout");
        }
    }

    private static Duration positive(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    public String provider() {
        return provider;
    }

    public String apiKey() {
        return apiKey;
    }

    public int defaultLimit() {
        return defaultLimit;
    }

    public SearchDepth defaultDepth() {
        return defaultDepth;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public Duration toolTimeout() {
        return toolTimeout;
    }

    public String proxy() {
        return proxy;
    }

    @Override
    public String toString() {
        return "WebSearchProperties[provider="
                + provider
                + ", apiKey=***, defaultLimit="
                + defaultLimit
                + ", defaultDepth="
                + defaultDepth
                + ", connectTimeout="
                + connectTimeout
                + ", requestTimeout="
                + requestTimeout
                + ", toolTimeout="
                + toolTimeout
                + ", proxy="
                + proxy
                + "]";
    }
}
