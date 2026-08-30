package com.datagent.agentv2.conversation.tool.web.fetch;

import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.List;
import org.springframework.stereotype.Component;

/** 使用 JDK HttpClient 获取有严格字节上限的单次响应，默认不继承系统代理。 */
@Component
final class JdkWebFetchTransport implements WebFetchTransport {

    private final WebFetchProperties properties;
    private final HttpClient client;

    JdkWebFetchTransport(WebFetchProperties properties) {
        this.properties = properties;
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(properties.connectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .proxy(noProxy())
                        .build();
    }

    @Override
    public WebFetchHttpResponse get(URI uri) throws WebFetchException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder(uri)
                        .timeout(properties.requestTimeout())
                        .header("User-Agent", properties.userAgent())
                        .header("Accept", "text/html,application/xhtml+xml,text/plain")
                        .header("Accept-Encoding", "identity")
                        .GET()
                        .build();
        try {
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declaredLength > properties.maxResponseBytes()) {
                response.body().close();
                throw tooLarge();
            }
            try (InputStream body = response.body()) {
                byte[] bytes = body.readNBytes(properties.maxResponseBytes() + 1);
                if (bytes.length > properties.maxResponseBytes()) {
                    throw tooLarge();
                }
                return new WebFetchHttpResponse(
                        response.statusCode(), response.headers().map(), bytes);
            }
        } catch (HttpTimeoutException failure) {
            throw new WebFetchException(
                    WebFetchException.Kind.TIMEOUT, "Web fetch request timed out", failure);
        } catch (IOException failure) {
            throw new WebFetchException(
                    WebFetchException.Kind.UNAVAILABLE, "Web fetch request failed", failure);
        }
    }

    private static ProxySelector noProxy() {
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                // 直连模式没有可切换的代理状态。
            }
        };
    }

    private static WebFetchException tooLarge() {
        return new WebFetchException(
                WebFetchException.Kind.RESPONSE_TOO_LARGE,
                "Web fetch response exceeded byte limit");
    }
}
