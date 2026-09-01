package com.datagent.agentv2.conversation.tool.web.tavily;

import com.datagent.agentv2.conversation.tool.web.WebSearchException;
import com.datagent.agentv2.conversation.tool.web.WebSearchProperties;
import com.datagent.agentv2.conversation.tool.web.WebSearchProvider;
import com.datagent.agentv2.conversation.tool.web.WebSearchQuery;
import com.datagent.agentv2.conversation.tool.web.WebSearchResult;
import com.datagent.agentv2.conversation.tool.web.WebSearchTelemetry;
import com.datagent.agentv2.conversation.tool.web.WebSearchTelemetryEvent;
import com.datagent.agentv2.conversation.tool.web.transport.WebSearchHttpRequest;
import com.datagent.agentv2.conversation.tool.web.transport.WebSearchHttpResponse;
import com.datagent.agentv2.conversation.tool.web.transport.WebSearchTransport;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 将统一搜索请求映射到固定 Tavily Search API，不开放 Endpoint 或额外取数选项。 */
@Component
@RequiredArgsConstructor
public final class TavilyWebSearchProvider implements WebSearchProvider {

    private static final URI ENDPOINT = URI.create("https://api.tavily.com/search");

    private final WebSearchTransport transport;
    private final WebSearchProperties properties;
    private final ObjectMapper json;
    private final WebSearchTelemetry telemetry;

    @Override
    public String id() {
        return "tavily";
    }

    @Override
    public List<WebSearchResult> search(WebSearchQuery query)
            throws WebSearchException, InterruptedException {
        long started = System.nanoTime();
        Integer status = null;
        int resultCount = 0;
        String resultCode = "unavailable";
        String requestId = null;
        try {
            WebSearchHttpResponse response = transport.send(request(query));
            status = response.statusCode();
            classifyStatus(status);
            JsonNode root = parse(response.body());
            if (root == null || !root.isObject()) {
                throw new WebSearchException(
                        WebSearchException.Kind.INVALID_RESPONSE,
                        "Tavily response root must be an object");
            }
            JsonNode resultsNode = root.get("results");
            if (resultsNode == null || !resultsNode.isArray()) {
                throw new WebSearchException(
                        WebSearchException.Kind.INVALID_RESPONSE,
                        "Tavily response is missing results");
            }
            requestId = textOrNull(root.get("request_id"));
            List<WebSearchResult> results = new ArrayList<>();
            for (JsonNode item : resultsNode) {
                Double score =
                        item.has("score") && item.get("score").isNumber()
                                ? item.get("score").asDouble()
                                : null;
                results.add(
                        new WebSearchResult(
                                textOrNull(item.get("title")),
                                textOrNull(item.get("url")),
                                textOrNull(item.get("content")),
                                score));
            }
            resultCount = results.size();
            resultCode = "success";
            return List.copyOf(results);
        } catch (HttpTimeoutException failure) {
            resultCode = "timeout";
            throw new WebSearchException(
                    WebSearchException.Kind.TIMEOUT, "Tavily request timed out", failure);
        } catch (IOException failure) {
            resultCode = "unavailable";
            throw new WebSearchException(
                    WebSearchException.Kind.UNAVAILABLE, "Tavily request failed", failure);
        } catch (WebSearchException failure) {
            resultCode = failure.kind().name().toLowerCase(java.util.Locale.ROOT);
            throw failure;
        } finally {
            long durationMillis = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
            telemetry.record(
                    new WebSearchTelemetryEvent(
                            id(),
                            query.depth(),
                            durationMillis,
                            status,
                            resultCount,
                            resultCode,
                            requestId));
        }
    }

    private WebSearchHttpRequest request(WebSearchQuery query) {
        ObjectNode payload = json.createObjectNode();
        payload.put("query", query.query());
        payload.put("max_results", query.limit());
        payload.put("search_depth", query.depth().value());
        payload.put("include_answer", false);
        payload.put("include_raw_content", false);
        payload.put("include_images", false);
        return new WebSearchHttpRequest(
                "POST",
                ENDPOINT,
                Map.of(
                        "Authorization",
                        "Bearer " + properties.apiKey(),
                        "Content-Type",
                        "application/json"),
                json.writeValueAsString(payload),
                properties.requestTimeout());
    }

    private JsonNode parse(String body) throws WebSearchException {
        try {
            return json.readTree(body == null ? "" : body);
        } catch (JacksonException | IllegalArgumentException failure) {
            throw new WebSearchException(
                    WebSearchException.Kind.INVALID_RESPONSE,
                    "Tavily returned invalid JSON",
                    failure);
        }
    }

    private static void classifyStatus(int status) throws WebSearchException {
        if (status >= 200 && status < 300) {
            return;
        }
        WebSearchException.Kind kind =
                switch (status) {
                    case 401, 403 -> WebSearchException.Kind.AUTHENTICATION;
                    case 429 -> WebSearchException.Kind.RATE_LIMITED;
                    default -> WebSearchException.Kind.UNAVAILABLE;
                };
        throw new WebSearchException(kind, "Tavily returned HTTP " + status);
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asString() : null;
    }
}
