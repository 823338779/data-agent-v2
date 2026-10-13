package com.datagent.agentv2.dbt.client;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.dbt.config.DbtProperties;
import com.datagent.agentv2.dbt.model.DbtContracts.*;

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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;

/** 固定服务基址，禁止重定向及隐式重试；总截止时间覆盖完整响应读取。 */
@RequiredArgsConstructor
public final class HttpDbtServiceClient implements DbtServiceClient, AutoCloseable {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final String GET = "GET",
            POST = "POST",
            JSON = "application/json",
            ACCEPT = "Accept";
    private static final String UNAVAILABLE = "DBT_UNAVAILABLE", INVALID = "DBT_PROTOCOL_INVALID";
    private static final String BUILDS = "/builds",
            DEPLOYMENTS = "/deployments",
            CURRENT = "/current";
    private static final String CATALOG = "/catalog",
            RESOURCES = "/resources/",
            SOURCE = "/source",
            LINEAGE = "/lineage",
            NATIVE = "/native-details";
    private static final String OPTIONS = "/query/available-dimensions",
            QUERY = "/query",
            BUILD_ID = "build_id";
    private static final String AUTHORIZATION = "Authorization",
            BEARER = "Bearer ",
            SLASH = "/",
            ERROR_FIELD = "error",
            CODE = "code";
    private static final String REPOSITORY = "repository",
            ENVIRONMENT = "environmentId",
            BRANCH = "branchName",
            CURSOR = "cursor",
            LIMIT = "limit",
            Q = "q",
            KIND = "kind";
    private static final String QUESTION = "?",
            AMP = "&",
            EQUALS = "=",
            EMPTY = "",
            PLUS = "+",
            SPACE = "%20",
            TRAILING_SLASH = "/+$";

    /** 固定部署配置，凭据不进入任何响应。 */
    private final DbtProperties properties;

    /** 统一协议序列化器。 */
    private final ObjectMapper json;

    /** 每个请求的完整读取在可取消虚拟线程内进行。 */
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public BuildView submitBuild(BuildRequest body) throws Exception {
        BuildView result = call(POST, BUILDS, body, BuildView.class);
        if (result.buildId() == null
                || result.buildStatus() == null
                || !Objects.equals(body.repository(), result.repository())
                || !Objects.equals(body.branchName(), result.branchName())
                || !Objects.equals(body.execution(), result.execution())
                || !Objects.equals(body.commitSha(), result.requestedCommitSha()))
            throw protocolError();
        return result;
    }

    @Override
    public BuildView getBuild(UUID id) throws Exception {
        BuildView result = call(GET, BUILDS + SLASH + id, null, BuildView.class);
        same(id, result.buildId());
        if (result.buildStatus() == null || result.execution() == null) throw protocolError();
        return result;
    }

    @Override
    public DeploymentTargetView currentDeployment(DeploymentKey key) throws Exception {
        DeploymentTargetView result =
                call(
                        GET,
                        DEPLOYMENTS
                                + CURRENT
                                + query(
                                        REPOSITORY,
                                        key.repository(),
                                        ENVIRONMENT,
                                        key.environmentId(),
                                        BRANCH,
                                        key.branchName()),
                        null,
                        DeploymentTargetView.class);
        if (!Objects.equals(key.repository(), result.repository())
                || !Objects.equals(key.environmentId(), result.environmentId())
                || !Objects.equals(key.branchName(), result.branchName())) throw protocolError();
        return result;
    }

    @Override
    public DeploymentPage listDeployments(DeploymentFilter f) throws Exception {
        pageLimit(f.limit());
        return call(
                GET,
                DEPLOYMENTS
                        + query(
                                REPOSITORY,
                                f.repository(),
                                ENVIRONMENT,
                                f.environmentId(),
                                BRANCH,
                                f.branchName(),
                                CURSOR,
                                f.cursor(),
                                LIMIT,
                                f.limit()),
                null,
                DeploymentPage.class);
    }

    @Override
    public CatalogPage catalog(UUID id, CatalogFilter f) throws Exception {
        pageLimit(f.limit());
        CatalogPage result =
                call(
                        GET,
                        BUILDS
                                + SLASH
                                + id
                                + CATALOG
                                + query(
                                        Q,
                                        f.q(),
                                        KIND,
                                        f.kind(),
                                        CURSOR,
                                        f.cursor(),
                                        LIMIT,
                                        f.limit()),
                        null,
                        CatalogPage.class);
        same(id, result.buildId());
        return result;
    }

    @Override
    public ResourceView resource(UUID id, String resource) throws Exception {
        ResourceView result = call(GET, resourcePath(id, resource), null, ResourceView.class);
        same(id, result.buildId());
        same(resource, result.resourceId());
        return result;
    }

    @Override
    public ResourceSourceView resourceSource(UUID id, String resource) throws Exception {
        ResourceSourceView result =
                call(GET, resourcePath(id, resource) + SOURCE, null, ResourceSourceView.class);
        same(id, result.buildId());
        same(resource, result.resourceId());
        return result;
    }

    @Override
    public ResourceLineageView resourceLineage(UUID id, String resource) throws Exception {
        ResourceLineageView result =
                call(GET, resourcePath(id, resource) + LINEAGE, null, ResourceLineageView.class);
        same(id, result.buildId());
        same(resource, result.resourceId());
        return result;
    }

    @Override
    public ResourceNativeView resourceNative(UUID id, String resource) throws Exception {
        ResourceNativeView result =
                call(GET, resourcePath(id, resource) + NATIVE, null, ResourceNativeView.class);
        same(id, result.buildId());
        same(resource, result.resourceId());
        return result;
    }

    @Override
    public AvailableDimensionsView submitOptions(UUID id, AvailableDimensionsRequest body)
            throws Exception {
        AvailableDimensionsView result =
                call(POST, OPTIONS + query(BUILD_ID, id), body, AvailableDimensionsView.class);
        same(id, result.buildId());
        if (result.metricResourceIds() == null || result.dimensions() == null)
            throw protocolError();
        return result;
    }

    @Override
    public QueryResult submitQuery(UUID id, QueryRequest body) throws Exception {
        QueryResult result = call(POST, QUERY + query(BUILD_ID, id), body, QueryResult.class);
        same(id, result.buildId());
        // 同步响应没有任务身份和分页；校验固定构建、模式与完整结果结构。
        if (result.mode() != body.mode() || result.columns() == null || result.rows() == null)
            throw protocolError();
        return result;
    }

    private static void same(Object expected, Object actual) throws ToolBusinessException {
        if (expected == null || !expected.equals(actual)) throw protocolError();
    }

    private static void pageLimit(int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("分页范围必须为1至200");
    }

    private static String resourcePath(UUID id, String resource) {
        return BUILDS + SLASH + id + RESOURCES + part(resource);
    }

    private static String part(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace(PLUS, SPACE);
    }

    private static String query(Object... entries) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < entries.length; i += 2) {
            if (entries[i + 1] == null) continue;
            result.append(result.isEmpty() ? QUESTION : AMP)
                    .append(entries[i])
                    .append(EQUALS)
                    .append(part(entries[i + 1].toString()));
        }
        return result.toString();
    }

    private <T> T call(String method, String suffix, Object body, Class<T> responseType)
            throws Exception {
        String base = properties.baseUrl().toString().replaceAll(TRAILING_SLASH, EMPTY);
        var request = new HttpUriRequestBase(method, java.net.URI.create(base + suffix));
        request.setHeader(ACCEPT, JSON);
        // 所有受理写入均需要服务凭据；禁用重定向以免跨来源携带凭据。
        if (POST.equals(method)) {
            String token = properties.serviceToken();
            if (token == null || token.isBlank())
                throw new ToolBusinessException("DBT_SERVICE_NOT_CONFIGURED", "未配置dbt服务写入凭据");
            request.setHeader(AUTHORIZATION, BEARER + token);
        }
        if (body != null) {
            byte[] bytes = json.writeValueAsBytes(body);
            if (bytes.length > MAX_BYTES)
                throw new ToolBusinessException("DBT_REQUEST_TOO_LARGE", "请求超过8MiB上限");
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
        Future<T> pending = null;
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
                                                    detail = json.readTree(raw);
                                                    if (detail.has(ERROR_FIELD))
                                                        detail = detail.path(ERROR_FIELD);
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
                                    try {
                                        JsonNode tree = json.readTree(raw);
                                        if (tree == null || !tree.isObject()) throw protocolError();
                                        return json.treeToValue(tree, responseType);
                                    } catch (RuntimeException malformed) {
                                        throw protocolError();
                                    }
                                } finally {
                                    request.cancel();
                                }
                            });
            return pending.get(properties.requestTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            throw new ToolBusinessException("DBT_TIMEOUT", "dbt-service 请求超时；构建重试保留幂等键，查询重试会重新执行");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof ToolBusinessException business) throw business;
            throw new ToolBusinessException(UNAVAILABLE, "dbt-service 暂不可用；构建重试保留幂等键，查询重试会重新执行");
        } finally {
            request.cancel();
            if (pending != null) pending.cancel(true);
            client.close(CloseMode.IMMEDIATE);
        }
    }

    private static ToolBusinessException protocolError() {
        return new ToolBusinessException(INVALID, "dbt-service响应不符合执行快照协议或超过大小限制");
    }

    private static ToolBusinessException mapped(int status, JsonNode error) {
        // 保留服务稳定错误码，原始message可能含执行详情，不直接进入模型。
        String code = error == null ? EMPTY : error.path(CODE).asString(EMPTY);
        if (!code.matches("[A-Z][A-Z0-9_]{0,127}")) code = UNAVAILABLE;
        return new ToolBusinessException(code, "dbt-service请求失败，HTTP状态：" + status);
    }

    @Override
    public void close() {
        requests.shutdownNow();
    }
}
