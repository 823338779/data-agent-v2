package com.datagent.agentv2.dbt.tool;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.dbt.client.DbtServiceClient;
import com.datagent.agentv2.dbt.model.DbtToolOutput;
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
            case GET -> "读取查询状态及结果页；按返回 nextOffset 继续，不重新执行 SQL。";
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
                number(p, "limit", 1, 200);
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
        JsonNode project = client.project();
        if (!project.path("protocolVersion").asString().equals("agent-dbt-v1")) throw unsupported();
        Set<String> capabilities = new HashSet<>();
        project.path("capabilities").forEach(item -> capabilities.add(item.asString()));
        if (!capabilities.containsAll(
                Set.of("query-options-async-v1", "query-results-page-v1", "query-time-v1")))
            throw unsupported();
        JsonNode result =
                switch (kind) {
                    case PROJECT -> project;
                    case CATALOG ->
                            client.catalog(
                                    args.path(RELEASE).asString(),
                                    args.path("query").asString(""),
                                    args.path("page").asInt(1),
                                    args.path("size").asInt(20));
                    case RESOURCE -> resource(args);
                    case OPTIONS -> {
                        if (args.has(OPTIONS)) {
                            if (args.has(RELEASE) || args.has(METRICS))
                                throw new IllegalArgumentException("轮询选项只需 optionsJobId");
                            yield client.getOptions(args.path(OPTIONS).asString());
                        }
                        if (!args.has(RELEASE) || !args.has(METRICS))
                            throw new IllegalArgumentException("查询选项必须提供发布和指标集合");
                        yield client.submitOptions(args);
                    }
                    case SUBMIT -> {
                        ObjectNode request = (ObjectNode) args.deepCopy();
                        request.put(KEY, queryKey(context.executionId(), request, json));
                        yield client.submitQuery(request);
                    }
                    case GET -> {
                        String id = args.path(QUERY).asString();
                        JsonNode status = client.queryStatus(id);
                        checkTask(status, QUERY, id);
                        yield status.path("resultAvailable").asBoolean(false)
                                ? client.queryPage(
                                        id,
                                        args.path("offset").asInt(0),
                                        args.path("limit").asInt(100))
                                : status;
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

    private JsonNode resource(JsonNode args) throws Exception {
        ObjectNode resource =
                (ObjectNode)
                        client.resource(
                                        args.path(RELEASE).asString(),
                                        args.path(RESOURCE).asString(),
                                        args.path(VIEW).asString("detail"))
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
