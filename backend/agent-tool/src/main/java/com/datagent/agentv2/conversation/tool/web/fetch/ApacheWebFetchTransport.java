package com.datagent.agentv2.conversation.tool.web.fetch;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/** 使用成熟 HTTP 客户端绑定本跳已校验 IP，保留原 Host/TLS 身份，并限制包含正文读取的总时间。 */
@Component
@RequiredArgsConstructor
final class ApacheWebFetchTransport implements WebFetchTransport, DisposableBean {
    private final WebFetchProperties properties;
    private final ExecutorService requests =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("web-fetch-", 0).factory());

    @Override
    public WebFetchHttpResponse get(ValidatedWebAddress target)
            throws WebFetchException, InterruptedException {
        HttpGet request = new HttpGet(target.uri());
        request.setHeader("User-Agent", properties.userAgent());
        request.setHeader("Accept", "text/html,application/xhtml+xml,text/plain");
        request.setHeader("Accept-Encoding", "identity");
        CloseableHttpClient client = client(target);
        Future<WebFetchHttpResponse> pending = null;
        try {
            pending = requests.submit(() -> read(client, request));
            // Socket 的空闲超时不能限制持续滴流；此截止时间覆盖连接、响应头和全部正文读取。
            return pending.get(properties.requestTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            throw new WebFetchException(
                    WebFetchException.Kind.TIMEOUT, "Web fetch request timed out", failure);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof WebFetchException classified) {
                throw classified;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new WebFetchException(
                    cause instanceof SocketTimeoutException
                            ? WebFetchException.Kind.TIMEOUT
                            : WebFetchException.Kind.UNAVAILABLE,
                    "Web fetch request failed",
                    cause);
        } finally {
            // 先取消底层请求，主动断开阻塞读的 socket，再取消任务/关闭客户端；不能先等待流自然读完。
            request.cancel();
            if (pending != null) {
                pending.cancel(true);
            }
            client.close(CloseMode.IMMEDIATE);
        }
    }

    private CloseableHttpClient client(ValidatedWebAddress target) {
        var connections =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(new PinnedDnsResolver(target))
                        .setDefaultConnectionConfig(
                                ConnectionConfig.custom()
                                        .setConnectTimeout(Timeout.of(properties.connectTimeout()))
                                        .setSocketTimeout(Timeout.of(properties.requestTimeout()))
                                        .build())
                        .build();
        // 每跳拥有独立连接，避免复用上一次 DNS 授权下建立的 socket。保留框架默认 TLS/主机名验证；
        // 不启用系统代理、自动重定向、重试或内容解压，这些行为会绕开上层的逐跳地址/字节边界。
        return HttpClients.custom()
                .setConnectionManager(connections)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableContentCompression()
                .disableCookieManagement()
                .build();
    }

    private WebFetchHttpResponse read(CloseableHttpClient client, HttpGet request)
            throws IOException, WebFetchException {
        try (var response = client.executeOpen(null, request, null)) {
            try {
                Map<String, List<String>> headers = new LinkedHashMap<>();
                for (var header : response.getHeaders()) {
                    headers.computeIfAbsent(header.getName(), ignored -> new ArrayList<>())
                            .add(header.getValue());
                }
                var entity = response.getEntity();
                if (entity == null) {
                    return new WebFetchHttpResponse(response.getCode(), headers, new byte[0]);
                }
                if (entity.getContentLength() > properties.maxResponseBytes()) {
                    throw tooLarge();
                }
                // response 拥有 entity 流；超限时先在 finally 断开连接，避免 close 为复用连接而排空巨大正文。
                byte[] bytes = entity.getContent().readNBytes(properties.maxResponseBytes() + 1);
                if (bytes.length > properties.maxResponseBytes()) {
                    throw tooLarge();
                }
                return new WebFetchHttpResponse(response.getCode(), headers, bytes);
            } finally {
                request.cancel();
            }
        }
    }

    @Override
    public void destroy() {
        requests.shutdownNow();
    }

    private static WebFetchException tooLarge() {
        return new WebFetchException(
                WebFetchException.Kind.RESPONSE_TOO_LARGE,
                "Web fetch response exceeded byte limit");
    }

    /** 仅返回本次通过策略验证的地址，连 canonical hostname 查询也不触发额外 DNS。 */
    private record PinnedDnsResolver(ValidatedWebAddress target) implements DnsResolver {
        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            requireHost(host);
            return target.addresses().toArray(InetAddress[]::new);
        }

        @Override
        public String resolveCanonicalHostname(String host) throws UnknownHostException {
            requireHost(host);
            return host;
        }

        private void requireHost(String host) throws UnknownHostException {
            String expected = target.uri().getHost().replace("[", "").replace("]", "");
            if (!expected.equalsIgnoreCase(host.replace("[", "").replace("]", ""))) {
                throw new UnknownHostException("Host is outside the validated request");
            }
        }
    }
}
