package com.datagent.agentv2.conversation.tool.web.transport;

import com.datagent.agentv2.conversation.tool.web.WebSearchProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

/** 使用 JDK HttpClient 实现无重试、禁重定向且默认不继承系统代理的同步 Transport。 */
@Component
public final class JdkWebSearchTransport implements WebSearchTransport {

    private final HttpClient client;

    public JdkWebSearchTransport(WebSearchProperties properties) {
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(properties.connectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .proxy(proxySelector(properties.proxy()))
                        .build();
    }

    @Override
    public WebSearchHttpResponse send(WebSearchHttpRequest request)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(request.uri())
                        .timeout(request.timeout())
                        .method(
                                request.method(),
                                HttpRequest.BodyPublishers.ofString(
                                        request.body(), StandardCharsets.UTF_8));
        request.headers().forEach(builder::header);
        HttpResponse<String> response =
                client.send(
                        builder.build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new WebSearchHttpResponse(
                response.statusCode(), response.headers().map(), response.body());
    }

    private static ProxySelector proxySelector(String configuredProxy) {
        if (configuredProxy == null || configuredProxy.isBlank()) {
            return new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    return List.of(Proxy.NO_PROXY);
                }

                @Override
                public void connectFailed(URI uri, SocketAddress sa, IOException failure) {
                    // NO_PROXY 不会建立代理连接，因此没有需要恢复或改写的状态。
                }
            };
        }
        URI proxy;
        try {
            proxy = URI.create(configuredProxy);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("proxy must be http(s)://host:port", failure);
        }
        boolean supportedScheme =
                "http".equalsIgnoreCase(proxy.getScheme())
                        || "https".equalsIgnoreCase(proxy.getScheme());
        if (!supportedScheme
                || proxy.getHost() == null
                || proxy.getPort() < 1
                || proxy.getUserInfo() != null
                || proxy.getPath() != null && !proxy.getPath().isEmpty()
                || proxy.getQuery() != null
                || proxy.getFragment() != null) {
            throw new IllegalArgumentException("proxy must be http(s)://host:port");
        }
        return ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort()));
    }
}
