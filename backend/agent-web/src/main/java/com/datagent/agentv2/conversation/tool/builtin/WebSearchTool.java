package com.datagent.agentv2.conversation.tool.builtin;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.conversation.tool.AgentTool;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 通过无需密钥的 MediaWiki Action API 检索中文 Wikipedia 页面。 */
@Component
@Order(120)
public final class WebSearchTool implements AgentTool {

    /** 注册给模型的稳定工具名称。 */
    private static final String TOOL_NAME = "web_search";

    /** 明确检索范围和返回内容，防止模型把它误认为通用浏览器。 */
    private static final String TOOL_DESCRIPTION =
            "Search Chinese Wikipedia and return page titles, plain-text snippets, and URLs.";

    /** 限制 query 和 limit 的模型参数 Schema。 */
    private static final String PARAMETERS_SCHEMA =
            """
            {
              "type":"object",
              "additionalProperties":false,
              "required":["query"],
              "properties":{
                "query":{"type":"string","minLength":1,"maxLength":200},
                "limit":{"type":"integer","minimum":1,"maximum":5,"default":3}
              }
            }
            """;

    /** MediaWiki API 所在站点。 */
    private static final String API_BASE_URL = "https://zh.wikipedia.org";

    /** MediaWiki Action API 的固定路径。 */
    private static final String API_PATH = "/w/api.php";

    /** 返回结果中公开的检索来源。 */
    private static final String SOURCE_NAME = "zh.wikipedia.org";

    /** Wikipedia 页面链接前缀。 */
    private static final String ARTICLE_URL_PREFIX = "https://zh.wikipedia.org/wiki/";

    /** 向 Wikimedia 标识本服务用途的请求头。 */
    private static final String HEADER_USER_AGENT = "User-Agent";

    private static final String USER_AGENT = "Data-Agent-V2/0.1 (ReAct tool verification)";

    /** MediaWiki Action API 的固定查询参数。 */
    private static final String PARAM_ACTION = "action";

    private static final String VALUE_ACTION_QUERY = "query";
    private static final String PARAM_LIST = "list";
    private static final String VALUE_LIST_SEARCH = "search";
    private static final String PARAM_SEARCH = "srsearch";
    private static final String PARAM_LIMIT = "srlimit";
    private static final String PARAM_FORMAT = "format";
    private static final String VALUE_FORMAT_JSON = "json";
    private static final String PARAM_UTF8 = "utf8";
    private static final String VALUE_UTF8 = "1";

    /** 工具输入、上游响应和下游输出使用的 JSON 字段。 */
    private static final String KEY_QUERY = "query";

    private static final String KEY_LIMIT = "limit";
    private static final String KEY_SEARCH = "search";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_RESULTS = "results";
    private static final String KEY_TITLE = "title";
    private static final String KEY_SNIPPET = "snippet";
    private static final String KEY_URL = "url";

    /** 参数与输出的数量和长度边界。 */
    private static final int DEFAULT_LIMIT = 3;

    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 5;
    private static final int MAX_QUERY_LENGTH = 200;

    /** 协调器允许单次网页检索等待的最长时间。 */
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(10);

    /** 清理 MediaWiki 摘要中的搜索高亮标签。 */
    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]+>");

    /** 合并摘要中连续空白，保持 observation 紧凑。 */
    private static final Pattern REPEATED_WHITESPACE = Pattern.compile("\\s+");

    /** 构造 Wikipedia 页面路径时使用的空格和下划线。 */
    private static final String SPACE = " ";

    private static final String UNDERSCORE = "_";

    /** 上游不可用时返回给 ReAct 模型的稳定业务错误。 */
    private static final String FAILURE_CODE = "web_search_unavailable";

    private static final String FAILURE_MESSAGE = "Wikipedia search is temporarily unavailable";

    /** 执行无状态 MediaWiki GET 请求的同步客户端。 */
    private final RestClient client;

    /** 用于解析参数 Schema 和构造 observation。 */
    private final ObjectMapper json;

    /** 使用 Spring 管理的客户端配置创建 Wikipedia 检索工具。 */
    public WebSearchTool(RestClient.Builder restClientBuilder, ObjectMapper json) {
        this.client = restClientBuilder.baseUrl(API_BASE_URL).build();
        this.json = json;
    }

    /** 返回模型协议中使用的稳定工具名称。 */
    @Override
    public String name() {
        return TOOL_NAME;
    }

    /** 返回面向模型的检索范围说明。 */
    @Override
    public String description() {
        return TOOL_DESCRIPTION;
    }

    /** 声明查询文本必填、结果数量可选且有严格上限。 */
    @Override
    public JsonNode parametersSchema() {
        return json.readTree(PARAMETERS_SCHEMA);
    }

    /** 限制单次网络调用，避免外部服务长期占用 ReAct 执行槽。 */
    @Override
    public Duration timeout() {
        return TOOL_TIMEOUT;
    }

    /** 校验模型参数、调用 MediaWiki，并返回可直接消费的纯文本结果。 */
    @Override
    public JsonNode execute(JsonNode arguments, InvocationContext context) throws Exception {
        String query = requiredQuery(arguments);
        int limit = resultLimit(arguments);

        try {
            // 只调用公开的全文检索 API，不下载页面正文，也不携带租户数据或凭据。
            String responseBody =
                    client.get()
                            .uri(
                                    builder ->
                                            builder.path(API_PATH)
                                                    .queryParam(PARAM_ACTION, VALUE_ACTION_QUERY)
                                                    .queryParam(PARAM_LIST, VALUE_LIST_SEARCH)
                                                    .queryParam(PARAM_SEARCH, query)
                                                    .queryParam(PARAM_LIMIT, limit)
                                                    .queryParam(PARAM_FORMAT, VALUE_FORMAT_JSON)
                                                    .queryParam(PARAM_UTF8, VALUE_UTF8)
                                                    .build())
                            .header(HEADER_USER_AGENT, USER_AGENT)
                            .retrieve()
                            .body(String.class);
            if (responseBody == null || responseBody.isBlank()) {
                throw unavailable();
            }
            return result(query, json.readTree(responseBody));
        } catch (RestClientException failure) {
            throw unavailable();
        }
    }

    /** 读取并校验必填查询文本，避免向上游发送空请求或超长请求。 */
    private static String requiredQuery(JsonNode arguments) {
        JsonNode value = arguments.path(KEY_QUERY);
        if (!value.isTextual()) {
            throw new IllegalArgumentException("query must be a string");
        }
        String query = value.asString().trim();
        if (query.isEmpty() || query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("query length must be between 1 and 200");
        }
        return query;
    }

    /** 读取可选结果数量并强制限制在轻量检索范围内。 */
    private static int resultLimit(JsonNode arguments) {
        JsonNode value = arguments.path(KEY_LIMIT);
        if (value.isMissingNode()) {
            return DEFAULT_LIMIT;
        }
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException("limit must be an integer");
        }
        int limit = value.asInt();
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and 5");
        }
        return limit;
    }

    /** 将 MediaWiki 响应映射为稳定、无 HTML 的领域 observation。 */
    private ObjectNode result(String query, JsonNode response) {
        ObjectNode result = json.createObjectNode();
        result.put(KEY_QUERY, query);
        result.put(KEY_SOURCE, SOURCE_NAME);
        ArrayNode results = result.putArray(KEY_RESULTS);

        // 仅复制模型回答所需字段，避免把上游内部元数据扩散进持久化上下文。
        for (JsonNode item : response.path(KEY_QUERY).path(KEY_SEARCH)) {
            String title = item.path(KEY_TITLE).asString();
            ObjectNode entry = results.addObject();
            entry.put(KEY_TITLE, title);
            entry.put(KEY_SNIPPET, plainText(item.path(KEY_SNIPPET).asString()));
            entry.put(KEY_URL, articleUrl(title));
        }
        return result;
    }

    /** 去掉摘要中的高亮标签、解码 HTML 实体并合并多余空白。 */
    private static String plainText(String snippet) {
        String withoutTags = HTML_TAGS.matcher(snippet).replaceAll(SPACE);
        String decoded = HtmlUtils.htmlUnescape(withoutTags);
        return REPEATED_WHITESPACE.matcher(decoded).replaceAll(SPACE).trim();
    }

    /** 将页面标题安全编码为可点击的中文 Wikipedia 地址。 */
    private static String articleUrl(String title) {
        String pathSegment = title.replace(SPACE, UNDERSCORE);
        return ARTICLE_URL_PREFIX + UriUtils.encodePathSegment(pathSegment, StandardCharsets.UTF_8);
    }

    /** 创建不暴露底层 HTTP 细节的稳定业务异常。 */
    private static ToolBusinessException unavailable() {
        return new ToolBusinessException(FAILURE_CODE, FAILURE_MESSAGE);
    }
}
