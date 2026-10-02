package com.datagent.agentv2.conversation.tool.web;

import java.time.Duration;
import java.util.Objects;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 服务端联网搜索配置；API Key 仅供 Provider 使用，字符串表示始终脱敏。 */
@ConfigurationProperties("data-agent.tool.web-search")
@Accessors(fluent = true)
@Getter
public final class WebSearchProperties {

    private final String provider;

    /** 仅在服务端发出搜索请求时使用；诊断字符串不得包含原文。 */
    private final String apiKey;

    /** 未指定 limit 时返回的最大搜索结果数，范围 1～20。 */
    private final int defaultLimit;

    private final SearchDepth defaultDepth;

    /** TCP 建连时间上限，必须不大于 requestTimeout。 */
    private final Duration connectTimeout;

    /** 单次供应商 HTTP 请求时间上限，必须小于外层工具总预算。 */
    private final Duration requestTimeout;

    /** 外层工具总时间预算，包含参数处理、供应商请求和输出转换。 */
    private final Duration toolTimeout;

    /** 显式配置的 HTTP(S) 代理；空字符串表示直连，不继承系统代理。 */
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
