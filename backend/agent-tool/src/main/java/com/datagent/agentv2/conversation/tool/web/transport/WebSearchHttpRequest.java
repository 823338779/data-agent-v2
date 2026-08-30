package com.datagent.agentv2.conversation.tool.web.transport;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 与供应商无关且便于测试的 HTTP 请求。 */
public record WebSearchHttpRequest(
        String method, URI uri, Map<String, String> headers, String body, Duration timeout) {

    public WebSearchHttpRequest {
        method = Objects.requireNonNull(method, "method");
        uri = Objects.requireNonNull(uri, "uri");
        headers = Map.copyOf(headers);
        body = Objects.requireNonNull(body, "body");
        timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /** 诊断文本保留请求形状，但对任何大小写的 Authorization Header 脱敏。 */
    @Override
    public String toString() {
        Map<String, String> safeHeaders = new LinkedHashMap<>();
        headers.forEach(
                (name, value) ->
                        safeHeaders.put(
                                name, "Authorization".equalsIgnoreCase(name) ? "***" : value));
        return "WebSearchHttpRequest[method="
                + method
                + ", uri="
                + uri
                + ", headers="
                + safeHeaders
                + ", bodyLength="
                + body.length()
                + ", timeout="
                + timeout
                + "]";
    }
}
