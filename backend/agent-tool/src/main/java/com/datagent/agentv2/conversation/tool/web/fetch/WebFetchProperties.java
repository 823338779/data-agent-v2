package com.datagent.agentv2.conversation.tool.web.fetch;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 服务端网页获取边界配置。
 *
 * @param connectTimeout TCP 连接超时
 * @param requestTimeout 单次 HTTP 请求超时
 * @param toolTimeout 外层 Tool 总超时，必须大于 requestTimeout
 * @param maxRedirects 手动重定向上限，每一跳都会重新执行地址安全检查
 * @param maxResponseBytes 最大响应体字节数
 * @param defaultMaxCharacters 默认返回给模型的正文字符数
 * @param userAgent 对外标识本服务用途的 User-Agent
 */
@ConfigurationProperties("data-agent.tool.web-fetch")
public record WebFetchProperties(
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("20s") Duration requestTimeout,
        @DefaultValue("25s") Duration toolTimeout,
        @DefaultValue("3") int maxRedirects,
        @DefaultValue("1048576") int maxResponseBytes,
        @DefaultValue("12000") int defaultMaxCharacters,
        @DefaultValue("Data-Agent-V2/0.1 web_fetch") String userAgent) {

    public WebFetchProperties {
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(requestTimeout, "requestTimeout");
        requirePositive(toolTimeout, "toolTimeout");
        if (connectTimeout.compareTo(requestTimeout) > 0
                || requestTimeout.compareTo(toolTimeout) >= 0) {
            throw new IllegalArgumentException(
                    "timeouts must satisfy connectTimeout <= requestTimeout < toolTimeout");
        }
        if (maxRedirects < 0 || maxRedirects > 5) {
            throw new IllegalArgumentException("maxRedirects must be between 0 and 5");
        }
        if (maxResponseBytes < 65_536 || maxResponseBytes > 5_242_880) {
            throw new IllegalArgumentException(
                    "maxResponseBytes must be between 65536 and 5242880");
        }
        if (defaultMaxCharacters < 1_000 || defaultMaxCharacters > 20_000) {
            throw new IllegalArgumentException(
                    "defaultMaxCharacters must be between 1000 and 20000");
        }
        userAgent = Objects.requireNonNull(userAgent, "userAgent").trim();
        if (userAgent.isEmpty()) {
            throw new IllegalArgumentException("userAgent must not be blank");
        }
    }

    private static void requirePositive(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
