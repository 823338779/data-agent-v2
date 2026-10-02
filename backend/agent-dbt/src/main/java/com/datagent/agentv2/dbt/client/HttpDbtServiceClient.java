package com.datagent.agentv2.dbt.client;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.dbt.config.DbtProperties;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.*;
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 有界同步 HTTP 调用；虚拟线程总超时防止慢速正文无限占用工具执行。 */
@RequiredArgsConstructor
public final class HttpDbtServiceClient implements DbtServiceClient, AutoCloseable {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final String GET = "GET", POST = "POST", JSON = "application/json";
    private static final String UNAVAILABLE = "DBT_UNAVAILABLE", INVALID = "DBT_PROTOCOL_INVALID";
    private final DbtProperties properties;
    private final ObjectMapper json;
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();

    public JsonNode project() throws Exception {
        return call(GET, "/publication", null);
    }

    public JsonNode catalog(String r, String q, int page, int size) throws Exception {
        return call(
                GET,
                "/releases/"
                        + part(r)
                        + "/catalog?q="
                        + part(q)
                        + "&page="
                        + page
                        + "&size="
                        + size,
                null);
    }

    public JsonNode resource(String r, String id, String view) throws Exception {
        if (!Set.of("detail", "source", "lineage", "native-details").contains(view))
            throw new IllegalArgumentException("未知资源视图");
        return call(
                GET,
                "/releases/"
                        + part(r)
                        + "/resources/"
                        + part(id)
                        + (view.equals("detail") ? "" : "/" + view),
                null);
    }

    public JsonNode submitOptions(JsonNode body) throws Exception {
        return call(POST, "/query-option-jobs", body);
    }

    public JsonNode getOptions(String id) throws Exception {
        return call(GET, "/query-option-jobs/" + part(id), null);
    }

    public JsonNode submitQuery(JsonNode body) throws Exception {
        return call(POST, "/queries", body);
    }

    public JsonNode queryStatus(String id) throws Exception {
        return call(GET, "/queries/" + part(id) + "/status", null);
    }

    public JsonNode queryPage(String id, int offset, int limit) throws Exception {
        return call(
                GET,
                "/queries/" + part(id) + "/results?offset=" + offset + "&limit=" + limit,
                null);
    }

    public JsonNode submitValidation(JsonNode body) throws Exception {
        return call(POST, "/validations", body);
    }

    public JsonNode getValidation(String id) throws Exception {
        return call(GET, "/validations/" + part(id), null);
    }

    private static String part(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private JsonNode call(String method, String suffix, JsonNode body) throws Exception {
        String base = properties.baseUrl().toString().replaceAll("/+$", "");
        var request =
                new HttpUriRequestBase(
                        method,
                        java.net.URI.create(
                                base + "/v2/projects/" + part(properties.projectId()) + suffix));
        request.setHeader("Accept", JSON);
        if (body != null) {
            byte[] bytes = json.writeValueAsBytes(body);
            if (bytes.length > MAX_BYTES)
                throw new ToolBusinessException("DBT_REQUEST_TOO_LARGE", "请求超限，请减少修改文件或查询参数");
            request.setEntity(new ByteArrayEntity(bytes, ContentType.APPLICATION_JSON));
        }
        var connections =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(
                                ConnectionConfig.custom()
                                        .setConnectTimeout(Timeout.of(properties.connectTimeout()))
                                        .setSocketTimeout(Timeout.of(properties.requestTimeout()))
                                        .build())
                        .build();
        var client =
                HttpClients.custom()
                        .setConnectionManager(connections)
                        .disableAutomaticRetries()
                        .disableRedirectHandling()
                        .disableContentCompression()
                        .disableCookieManagement()
                        .build();
        Future<JsonNode> pending = null;
        try {
            pending =
                    requests.submit(
                            () -> {
                                try (var response = client.executeOpen(null, request, null)) {
                                    int status = response.getCode();
                                    var entity = response.getEntity();
                                    if (status < 200 || status >= 300) {
                                        // 仅接受固定诊断枚举和受限字段路径；服务原始 message 永不进入模型。
                                        JsonNode detail = null;
                                        if (entity != null
                                                && entity.getContentLength() <= MAX_BYTES) {
                                            byte[] raw =
                                                    entity.getContent().readNBytes(MAX_BYTES + 1);
                                            if (raw.length <= MAX_BYTES) {
                                                try {
                                                    detail = json.readTree(raw).path("detail");
                                                } catch (RuntimeException ignored) {
                                                    /* 非协议错误正文只按 HTTP 状态解释。 */
                                                }
                                            }
                                        }
                                        throw mapped(status, detail);
                                    }
                                    if (entity == null || entity.getContentLength() > MAX_BYTES)
                                        throw protocolError();
                                    byte[] raw = entity.getContent().readNBytes(MAX_BYTES + 1);
                                    if (raw.length > MAX_BYTES) throw protocolError();
                                    JsonNode result;
                                    try {
                                        result = json.readTree(raw);
                                    } catch (RuntimeException invalid) {
                                        throw protocolError();
                                    }
                                    if (result == null || !result.isObject()) throw protocolError();
                                    return result;
                                } finally {
                                    request.cancel();
                                }
                            });
            return pending.get(properties.requestTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            throw new ToolBusinessException("DBT_TIMEOUT", "dbt-service 请求超时，重试须使用原任务身份");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof ToolBusinessException business) throw business;
            throw new ToolBusinessException(UNAVAILABLE, "dbt-service 暂不可用，保留原任务身份后重试");
        } finally {
            request.cancel();
            if (pending != null) pending.cancel(true);
            client.close(CloseMode.IMMEDIATE);
        }
    }

    private static ToolBusinessException protocolError() {
        return new ToolBusinessException(INVALID, "dbt-service 响应不符合协议或超过大小限制");
    }

    private static ToolBusinessException mapped(int status, JsonNode detail) {
        var fallback =
                switch (status) {
                    case 404 -> new ToolBusinessException("DBT_NOT_FOUND", "项目、资源或任务不存在，请重新读取目录");
                    case 409 -> new ToolBusinessException("DBT_CONFLICT", "请求冲突或结果尚未就绪，请读取原任务状态");
                    case 410 -> new ToolBusinessException("DBT_RELEASE_GONE", "发布已替代，请重新读取目录及查询选项");
                    case 422 ->
                            new ToolBusinessException("DBT_INVALID_SELECTION", "参数无效，请重新核对定义或查询选项");
                    default ->
                            new ToolBusinessException(UNAVAILABLE, "dbt-service 暂不可用，保留原任务身份后重试");
                };
        if (detail == null || !detail.isObject()) return fallback;
        String reason = detail.path("reason").asString("");
        String recovery = detail.path("recovery").asString("");
        String field = detail.path("field").asString("");
        if (!Set.of(
                                "invalid_dimension_option",
                                "invalid_filter_option",
                                "invalid_metric_resource",
                                "invalid_mode_fields",
                                "invalid_preview_fields",
                                "invalid_order_field",
                                "invalid_time_range",
                                "result_not_ready",
                                "result_row_too_large",
                                "release_gone",
                                "idempotency_conflict",
                                "not_found",
                                "invalid_page")
                        .contains(reason)
                || !Set.of(
                                "reload_query_options",
                                "reload_catalog",
                                "fix_query_selection",
                                "fix_request",
                                "poll_query",
                                "reduce_query",
                                "fix_pagination",
                                "retry_same_key")
                        .contains(recovery)) return fallback;
        // 路径只允许已知查询字段和数组索引，不能利用错误字段携带任意文本。
        if (!field.matches(
                "(?:groupBy|filters|orderBy)(?:\\[[0-9]{1,3}\\])?(?:\\.(?:optionId|operator|fieldId|grain))?|metricResourceIds|mode|datasetResourceId|startTime|endTime|offset/limit|"))
            field = "";
        return new ToolBusinessException(
                fallback.code(),
                fallback.getMessage()
                        + "；reason="
                        + reason
                        + "，field="
                        + field
                        + "，recovery="
                        + recovery);
    }

    @Override
    public void close() {
        requests.shutdownNow();
    }
}
