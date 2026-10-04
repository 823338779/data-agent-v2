package com.datagent.agentv2.dbt.tool;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.dbt.client.DbtServiceClient;
import com.datagent.agentv2.dbt.model.DbtToolOutput;
import com.datagent.agentv2.git.port.GitWorkspaceResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 六种固定用途共用严格参数边界；模型无法提供 URL、projectId、SQL 或幂等键。 */
@RequiredArgsConstructor
public final class DbtAnalysisTool implements AgentTool {
    public enum Kind {
        PROJECT,
        CATALOG,
        RESOURCE,
        OPTIONS,
        SUBMIT,
        GET
    }

    public static final List<String> TOOL_IDS =
            List.of(
                    "dbt_project_get",
                    "dbt_catalog_search",
                    "dbt_resource_get",
                    "dbt_query_options",
                    "dbt_query_submit",
                    "dbt_query_get");
    private static final String RELEASE = "releaseId",
            RESOURCE = "resourceId",
            VIEW = "view",
            QUERY = "queryId",
            OPTIONS = "optionsJobId";
    private static final String METRICS = "metricResourceIds",
            KEY = "idempotencyKey",
            CONTENT = "content";
    private static final String OBJECT = "object",
            ARRAY = "array",
            STRING = "string",
            INTEGER = "integer";
    private static final String PROPERTIES = "properties", REQUIRED = "required", TYPE = "type";
    private static final String DEFAULT_RESOURCE_VIEW = "detail";
    private static final List<String> RESOURCE_VIEWS =
            List.of(DEFAULT_RESOURCE_VIEW, "source", "lineage", "native-details");
    private static final int MAX_RESULT_PAGE_SIZE = 200, MAX_QUERY_ROWS = 10000;
    private static final String QUERY_SCHEMA =
            """
        {"type":"object","additionalProperties":false,"required":["releaseId","mode"],"properties":{
          "releaseId":{"type":"string","minLength":1,"maxLength":64},
          "mode":{"type":"string","enum":["QUERY","EXPLAIN","DIMENSION_VALUES","PREVIEW"]},
          "metricResourceIds":{"type":"array","maxItems":100,"items":{"type":"string","maxLength":512}},
          "groupBy":{"type":"array","maxItems":100,"items":{"type":"object","required":["optionId"],"properties":{"optionId":{"type":"string","maxLength":128},"grain":{"type":"string","maxLength":32}}}},
          "filters":{"type":"array","maxItems":100,"items":{"type":"object","required":["optionId","operator","value"],"properties":{"optionId":{"type":"string","maxLength":128},"operator":{"type":"string","maxLength":32},"value":{}}}},
          "orderBy":{"type":"array","maxItems":100,"items":{"type":"object","required":["fieldId","direction"],"properties":{"fieldId":{"type":"string","maxLength":512},"direction":{"type":"string","enum":["ASC","DESC"]}}}},
          "startTime":{"type":"string","maxLength":64},"endTime":{"type":"string","maxLength":64},
          "limit":{"type":"integer","minimum":1,"maximum":10000},
          "datasetResourceId":{"type":"string","maxLength":512},"dimensionOptionId":{"type":"string","maxLength":128}
        }}
        """;
    private final Kind kind;
    private final DbtServiceClient client;
    private final ObjectMapper json;

    /** Git 启用时解析执行固定目标；无 Git 装配为空，保留线上分析。 */
    private final GitWorkspaceResolver workspaces;

    public DbtAnalysisTool(Kind kind, DbtServiceClient client, ObjectMapper json) {
        this(kind, client, json, null);
    }

    @Override
    public String name() {
        return TOOL_IDS.get(kind.ordinal());
    }

    @Override
    public boolean supportsIdempotency() {
        return true;
    }

    // 查询读取最多三个有界 HTTP 调用（能力、状态、结果），不在工具内长轮询。
    @Override
    public java.time.Duration timeout() {
        return java.time.Duration.ofSeconds(75);
    }

    @Override
    public String description() {
        return switch (kind) {
            case PROJECT -> "查看固定 dbt 项目、活动发布与服务能力；草稿不改变活动发布。";
            case CATALOG -> "按名称或口径搜索已发布资源，返回分页目录。";
            case RESOURCE -> "读取固定发布资源详情、源码或依赖；源码按字符分页。";
            case OPTIONS -> "获取指标集合的合法查询选项，或用 optionsJobId 继续轮询。";
            case SUBMIT -> "提交固定发布的指标查询、解释、维度值或数据集预览，不接收 SQL。";
            case GET -> "读取查询状态及结果页；每页最多 200 行，较大的 limit 自动封顶；按返回 nextOffset 继续，不重新执行 SQL。";
        };
    }

    @Override
    public JsonNode parametersSchema() {
        if (kind == Kind.SUBMIT) return json.readTree(QUERY_SCHEMA);
        ObjectNode schema =
                json.createObjectNode().put(TYPE, OBJECT).put("additionalProperties", false);
        var p = schema.putObject(PROPERTIES);
        var required = schema.putArray(REQUIRED);
        switch (kind) {
            case PROJECT -> {}
            case CATALOG -> {
                text(p, RELEASE, 64);
                required.add(RELEASE);
                text(p, "query", 1000);
                number(p, "page", 1, 100000);
                number(p, "size", 1, 50);
            }
            case RESOURCE -> {
                text(p, RELEASE, 64);
                text(p, RESOURCE, 512);
                required.add(RELEASE).add(RESOURCE);
                text(p, VIEW, 32);
                // 将客户端实际支持的视图暴露给模型，避免猜测 DETAIL 等无法路由的枚举值。
                var view = (ObjectNode) p.path(VIEW);
                view.put("default", DEFAULT_RESOURCE_VIEW);
                var views = view.putArray("enum");
                RESOURCE_VIEWS.forEach(views::add);
                number(p, "offset", 0, 10000000);
                number(p, "limit", 1, 10000);
            }
            case OPTIONS -> {
                text(p, RELEASE, 64);
                text(p, OPTIONS, 64);
                var items =
                        p.putObject(METRICS)
                                .put(TYPE, ARRAY)
                                .put("minItems", 1)
                                .put("maxItems", 100);
                items.putObject("items").put(TYPE, STRING).put("maxLength", 512);
            }
            case GET -> {
                text(p, QUERY, 64);
                required.add(QUERY);
                number(p, "offset", 0, 10000);
                number(p, "limit", 1, MAX_QUERY_ROWS);
            }
            default -> throw new IllegalStateException();
        }
        return schema;
    }

    private static void text(ObjectNode p, String name, int length) {
        p.putObject(name).put(TYPE, STRING).put("minLength", 1).put("maxLength", length);
    }

    private static void number(ObjectNode p, String name, int min, int max) {
        p.putObject(name).put(TYPE, INTEGER).put("minimum", min).put("maximum", max);
    }

    @Override
    public JsonNode execute(JsonNode args, InvocationContext context) throws Exception {
        validate(args, parametersSchema());
        if (context == null || context.executionId() == null || context.executionId().isBlank())
            throw new IllegalArgumentException("缺少可信执行身份");
        String branchId = workspaces == null ? null : workspaces.resolve(context).branchId();
        JsonNode project = branchId == null ? client.project() : client.project(branchId);
        if (!project.path("protocolVersion").asString().equals("agent-dbt-v1")) throw unsupported();
        Set<String> capabilities = new HashSet<>();
        project.path("capabilities").forEach(item -> capabilities.add(item.asString()));
        if (branchId != null
                && (!capabilities.contains("branch-development-v1")
                        || !branchId.equals(project.path("branchId").asString())))
            throw unsupported();
        if (!capabilities.containsAll(
                Set.of("query-options-async-v1", "query-results-page-v1", "query-time-v1")))
            throw unsupported();
        JsonNode result =
                switch (kind) {
                    case PROJECT -> project;
                    case CATALOG -> catalog(args, branchId);
                    case RESOURCE -> resource(args, branchId);
                    case OPTIONS -> {
                        if (args.has(OPTIONS)) {
                            if (args.has(RELEASE) || args.has(METRICS))
                                throw new IllegalArgumentException("轮询选项只需 optionsJobId");
                            yield branchId == null
                                    ? client.getOptions(args.path(OPTIONS).asString())
                                    : client.getOptions(branchId, args.path(OPTIONS).asString());
                        }
                        if (!args.has(RELEASE) || !args.has(METRICS))
                            throw new IllegalArgumentException("查询选项必须提供发布和指标集合");
                        yield branchId == null
                                ? client.submitOptions(args)
                                : client.submitOptions(branchId, args);
                    }
                    case SUBMIT -> {
                        ObjectNode request = (ObjectNode) args.deepCopy();
                        request.put(KEY, queryKey(context.executionId(), request, json));
                        yield branchId == null
                                ? client.submitQuery(request)
                                : client.submitQuery(branchId, request);
                    }
                    case GET -> {
                        String id = args.path(QUERY).asString();
                        JsonNode status =
                                branchId == null
                                        ? client.queryStatus(id)
                                        : client.queryStatus(branchId, id);
                        checkTask(status, QUERY, id);
                        // 模型常沿用提交查询的行数上限；只封顶传输页大小，保留服务实际游标避免漏行。
                        int pageSize =
                                Math.min(args.path("limit").asInt(100), MAX_RESULT_PAGE_SIZE);
                        if (!status.path("resultAvailable").asBoolean(false)) yield status;
                        int offset = args.path("offset").asInt(0);
                        yield branchId == null
                                ? client.queryPage(id, offset, pageSize)
                                : client.queryPage(branchId, id, offset, pageSize);
                    }
                };
        if (kind == Kind.OPTIONS)
            checkTask(result, OPTIONS, args.has(OPTIONS) ? args.path(OPTIONS).asString() : null);
        if (kind == Kind.SUBMIT || kind == Kind.GET)
            checkTask(result, QUERY, kind == Kind.GET ? args.path(QUERY).asString() : null);
        return DbtToolOutput.bounded(result, json);
    }

    private static void checkTask(JsonNode result, String identity, String expected)
            throws ToolBusinessException {
        String id = result.path(identity).asString("");
        if (id.isBlank()
                || expected != null && !expected.equals(id)
                || !Set.of("QUEUED", "RUNNING", "READY", "FAILED", "CANCELLED")
                        .contains(result.path("state").asString(""))) {
            throw new ToolBusinessException("DBT_PROTOCOL_INVALID", "任务响应缺少身份或包含未知状态，请检查服务协议版本");
        }
    }

    private JsonNode catalog(JsonNode args, String branchId) throws Exception {
        String release = args.path(RELEASE).asString();
        String query = args.path("query").asString("");
        int page = args.path("page").asInt(1), size = args.path("size").asInt(20);
        return branchId == null
                ? client.catalog(release, query, page, size)
                : client.catalog(branchId, release, query, page, size);
    }

    private JsonNode resource(JsonNode args, String branchId) throws Exception {
        String release = args.path(RELEASE).asString(), id = args.path(RESOURCE).asString();
        String view = args.path(VIEW).asString(DEFAULT_RESOURCE_VIEW);
        ObjectNode resource =
                (ObjectNode)
                        (branchId == null
                                        ? client.resource(release, id, view)
                                        : client.resource(branchId, release, id, view))
                                .deepCopy();
        if (resource.has(CONTENT)) {
            String content = resource.path(CONTENT).asString();
            int start = Math.min(args.path("offset").asInt(0), content.length());
            int end = Math.min(start + args.path("limit").asInt(8000), content.length());
            resource.put(CONTENT, content.substring(start, end))
                    .put("offset", start)
                    .put("totalCharacters", content.length());
            if (end < content.length()) resource.put("nextOffset", end);
            else resource.putNull("nextOffset");
        }
        return resource;
    }

    public static String queryKey(String execution, JsonNode args, ObjectMapper json) {
        ObjectNode canonical = (ObjectNode) args.deepCopy();
        canonical.remove(KEY);
        if (!canonical.has("limit")) canonical.put("limit", 1000);
        for (String field : List.of(METRICS, "groupBy", "filters", "orderBy"))
            if (!canonical.has(field)) canonical.putArray(field);
        for (String field : List.of("startTime", "endTime"))
            if (canonical.has(field)) {
                canonical.put(
                        field,
                        OffsetDateTime.parse(canonical.path(field).asString())
                                .toInstant()
                                .toString());
            }
        if (canonical.path(METRICS).isArray()) {
            SortedSet<String> selected = new TreeSet<>();
            canonical.path(METRICS).forEach(item -> selected.add(item.asString()));
            var array = canonical.putArray(METRICS);
            selected.forEach(array::add);
        }
        try {
            byte[] raw =
                    (execution + "\n" + json.writeValueAsString(sorted(canonical, json)))
                            .getBytes(StandardCharsets.UTF_8);
            return "agent-dbt:"
                    + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static JsonNode sorted(JsonNode value, ObjectMapper json) {
        if (value.isObject()) {
            var object = json.createObjectNode();
            new TreeSet<>(value.propertyNames())
                    .forEach(key -> object.set(key, sorted(value.path(key), json)));
            return object;
        }
        if (value.isArray()) {
            var array = json.createArrayNode();
            value.forEach(item -> array.add(sorted(item, json)));
            return array;
        }
        return value;
    }

    private static ToolBusinessException unsupported() {
        return new ToolBusinessException(
                "DBT_CAPABILITY_UNSUPPORTED", "dbt-service 版本缺少所需能力，请升级服务");
    }

    /** JSON Schema 和运行时双重校验；任意 JSON 筛选值仅交由服务执行类型检查。 */
    public static void validate(JsonNode value, JsonNode schema) {
        if (schema.isEmpty()) return;
        String type = schema.path(TYPE).asString();
        if (value == null
                || switch (type) {
                    case OBJECT -> !value.isObject();
                    case ARRAY -> !value.isArray();
                    case STRING -> !value.isTextual();
                    case INTEGER -> !value.isIntegralNumber() || !value.canConvertToInt();
                    default -> true;
                }) throw new IllegalArgumentException("dbt 参数类型无效");
        if (type.equals(OBJECT)) {
            for (String key : value.propertyNames()) {
                if (!schema.path(PROPERTIES).has(key))
                    throw new IllegalArgumentException("未知 dbt 参数");
                validate(value.path(key), schema.path(PROPERTIES).path(key));
            }
            for (JsonNode key : schema.path(REQUIRED))
                if (!value.has(key.asString())) throw new IllegalArgumentException("缺少 dbt 参数");
        } else if (type.equals(ARRAY)) {
            if (value.size() < schema.path("minItems").asInt(0)
                    || value.size() > schema.path("maxItems").asInt(100))
                throw new IllegalArgumentException("dbt 参数数量越界");
            value.forEach(item -> validate(item, schema.path("items")));
        } else if (type.equals(INTEGER)) {
            if (value.asInt() < schema.path("minimum").asInt(0)
                    || value.asInt() > schema.path("maximum").asInt(Integer.MAX_VALUE))
                throw new IllegalArgumentException("dbt 整数参数越界");
        } else if (type.equals(STRING)) {
            if (value.asString().length() < schema.path("minLength").asInt(0)
                    || value.asString().length() > schema.path("maxLength").asInt(1000))
                throw new IllegalArgumentException("dbt 文本参数越界");
            if (schema.has("enum")) {
                boolean found = false;
                for (JsonNode allowed : schema.path("enum")) found |= allowed.equals(value);
                if (!found) throw new IllegalArgumentException("dbt 枚举参数无效");
            }
        }
    }
}
