package com.datagent.agentv2.conversation.tool.web.fetch;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 手动处理重定向，并在每一跳重新验证目标地址和响应类型。 */
@Service
public final class SafeWebPageFetcher implements WebPageFetcher {

    private final WebFetchTransport transport;
    private final PublicWebAddressPolicy addresses;
    private final WebFetchProperties properties;

    SafeWebPageFetcher(
            WebFetchTransport transport,
            PublicWebAddressPolicy addresses,
            WebFetchProperties properties) {
        this.transport = transport;
        this.addresses = addresses;
        this.properties = properties;
    }

    @Override
    public WebFetchPage fetch(URI requested) throws WebFetchException, InterruptedException {
        URI current = requested;
        for (int redirect = 0; redirect <= properties.maxRedirects(); redirect++) {
            addresses.validate(current);
            WebFetchHttpResponse response = transport.get(current);
            if (isRedirect(response.statusCode())) {
                if (redirect == properties.maxRedirects()) {
                    throw new WebFetchException(
                            WebFetchException.Kind.INVALID_RESPONSE,
                            "Web fetch redirect limit exceeded");
                }
                String location = firstHeader(response.headers(), "location");
                if (location == null || location.isBlank()) {
                    throw new WebFetchException(
                            WebFetchException.Kind.INVALID_RESPONSE,
                            "Redirect response is missing Location");
                }
                try {
                    current = current.resolve(location);
                } catch (IllegalArgumentException failure) {
                    throw new WebFetchException(
                            WebFetchException.Kind.INVALID_RESPONSE,
                            "Redirect Location is invalid",
                            failure);
                }
                continue;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new WebFetchException(
                        WebFetchException.Kind.HTTP_ERROR,
                        "Web fetch returned HTTP " + response.statusCode());
            }
            String contentType = firstHeader(response.headers(), "content-type");
            if (!supportedContentType(contentType)) {
                throw new WebFetchException(
                        WebFetchException.Kind.UNSUPPORTED_CONTENT_TYPE,
                        "Web fetch content type is not supported");
            }
            return new WebFetchPage(current, contentType, response.body());
        }
        throw new IllegalStateException("Redirect loop terminated unexpectedly");
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean supportedContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return "text/html".equals(mediaType)
                || "application/xhtml+xml".equals(mediaType)
                || "text/plain".equals(mediaType);
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElse(null);
    }
}
