package com.datagent.agentv2.conversation.tool.builtin;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.conversation.tool.web.SearchDepth;
import com.datagent.agentv2.conversation.tool.web.WebSearchException;
import com.datagent.agentv2.conversation.tool.web.WebSearchProperties;
import com.datagent.agentv2.conversation.tool.web.WebSearchProvider;
import com.datagent.agentv2.conversation.tool.web.WebSearchProviderRegistry;
import com.datagent.agentv2.conversation.tool.web.WebSearchQuery;
import com.datagent.agentv2.conversation.tool.web.WebSearchResult;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 校验模型参数、调用服务端指定 Provider，并生成结果与引用严格对齐的联网搜索 Tool。 */
@Component
@Order(120)
public final class WebSearchTool implements AgentTool {

    private static final String PARAMETERS_SCHEMA =
            """
            {"type":"object","additionalProperties":false,"required":["query"],"properties":{"query":{"type":"string","minLength":1,"maxLength":400},"limit":{"type":"integer","minimum":1,"maximum":20},"search_depth":{"type":"string","enum":["basic","advanced"]}}}
            """;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final int MAX_QUERY_CODE_POINTS = 400;
    private static final int MAX_SNIPPET_CODE_POINTS = 500;

    private final WebSearchProviderRegistry providers;
    private final WebSearchProperties properties;
    private final ObjectMapper json;

    public WebSearchTool(
            WebSearchProviderRegistry providers,
            WebSearchProperties properties,
            ObjectMapper json) {
        this.providers = providers;
        this.properties = properties;
        this.json = json;
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "Search the public web and return normalized results with aligned references.";
    }

    @Override
    public JsonNode parametersSchema() {
        return json.readTree(PARAMETERS_SCHEMA);
    }

    @Override
    public Duration timeout() {
        return properties.toolTimeout();
    }

    @Override
    public JsonNode execute(JsonNode arguments, InvocationContext context) throws Exception {
        WebSearchQuery query = query(arguments);
        if (properties.apiKey().isBlank()) {
            throw new ToolBusinessException(
                    "web_search_not_configured", "Web search is not configured");
        }
        WebSearchProvider provider = providers.require(properties.provider());
        try {
            return output(query, provider.id(), provider.search(query));
        } catch (WebSearchException failure) {
            throw mapped(failure);
        }
    }

    private WebSearchQuery query(JsonNode arguments) {
        JsonNode queryNode = arguments.path("query");
        if (!queryNode.isTextual()) {
            throw new IllegalArgumentException("query must be a string");
        }
        String query = queryNode.asString().trim();
        int queryLength = query.codePointCount(0, query.length());
        if (queryLength < 1 || queryLength > MAX_QUERY_CODE_POINTS) {
            throw new IllegalArgumentException("query length must be between 1 and 400");
        }

        JsonNode limitNode = arguments.path("limit");
        int limit = properties.defaultLimit();
        if (!limitNode.isMissingNode()) {
            if (!limitNode.isIntegralNumber()) {
                throw new IllegalArgumentException("limit must be an integer");
            }
            limit = limitNode.asInt();
            if (limit < 1 || limit > 20) {
                throw new IllegalArgumentException("limit must be between 1 and 20");
            }
        }

        JsonNode depthNode = arguments.path("search_depth");
        SearchDepth depth = properties.defaultDepth();
        if (!depthNode.isMissingNode()) {
            if (!depthNode.isTextual()) {
                throw new IllegalArgumentException("search_depth must be a string");
            }
            depth = SearchDepth.fromValue(depthNode.asString());
        }
        return new WebSearchQuery(query, limit, depth);
    }

    private ObjectNode output(
            WebSearchQuery query, String provider, List<WebSearchResult> upstreamResults) {
        List<WebSearchResult> accepted =
                upstreamResults.stream()
                        .filter(result -> validWebUrl(result.url()))
                        .limit(query.limit())
                        .toList();
        ObjectNode output = json.createObjectNode();
        output.put("query", query.query());
        output.put("provider", provider);
        ArrayNode results = output.putArray("results");
        ArrayNode references = output.putArray("references");
        for (int offset = 0; offset < accepted.size(); offset++) {
            WebSearchResult result = accepted.get(offset);
            int index = offset + 1;
            String title = normalize(result.title());
            String snippet = truncate(normalize(result.snippet()), MAX_SNIPPET_CODE_POINTS);
            ObjectNode item = results.addObject();
            item.put("index", index);
            item.put("title", title);
            item.put("url", result.url());
            item.put("snippet", snippet);
            if (result.score() == null) {
                item.putNull("score");
            } else {
                item.put("score", result.score());
            }
            ObjectNode reference = references.addObject();
            reference.put("index", index);
            reference.put("title", title);
            reference.put("url", result.url());
        }
        return output;
    }

    private static boolean validWebUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return uri.isAbsolute()
                    && uri.getHost() != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : WHITESPACE.matcher(value).replaceAll(" ").trim();
    }

    private static String truncate(String value, int maximumCodePoints) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maximumCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maximumCodePoints);
        return value.substring(0, end) + "…";
    }

    private static ToolBusinessException mapped(WebSearchException failure) {
        String code =
                switch (failure.kind()) {
                    case AUTHENTICATION -> "web_search_authentication_failed";
                    case RATE_LIMITED -> "web_search_rate_limited";
                    case TIMEOUT -> "tool_timeout";
                    case UNAVAILABLE -> "web_search_unavailable";
                    case INVALID_RESPONSE -> "web_search_invalid_response";
                };
        return new ToolBusinessException(
                code, "Web search failed: " + code.toLowerCase(Locale.ROOT));
    }
}
