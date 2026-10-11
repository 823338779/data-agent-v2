package com.datagent.agentv2.dbt.tool;

import com.datagent.agentv2.conversation.exception.ToolBusinessException;
import com.datagent.agentv2.conversation.tool.AgentTool;
import com.datagent.agentv2.dbt.client.DbtServiceClient;
import com.datagent.agentv2.dbt.model.DbtContracts.*;
import com.datagent.agentv2.dbt.model.DbtToolOutput;
import com.datagent.agentv2.dbt.port.AnalysisSessionResolver;
import com.datagent.agentv2.dbt.port.BuildContextResolver;
import java.util.*;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 分析目标仅取可信会话，目录、选项、查询及结果全程固定同一构建。 */
@RequiredArgsConstructor
public final class DbtAnalysisTool implements AgentTool {
    public enum Kind {
        BUILD,
        CATALOG,
        RESOURCE,
        OPTIONS,
        SUBMIT
    }

    public static final List<String> TOOL_IDS =
            List.of(
                    "dbt_build_get",
                    "dbt_catalog_search",
                    "dbt_resource_get",
                    "dbt_query_options",
                    "dbt_query_submit");
    private static final String RESOURCE = "resourceId",
            VIEW = "view",
            METRICS = "metricResourceIds",
            CONTENT = "content",
            BUILD = "buildId";
    private static final String OBJECT = "object",
            ARRAY = "array",
            STRING = "string",
            INTEGER = "integer",
            PROPERTIES = "properties",
            REQUIRED = "required",
            TYPE = "type";
    private static final String DETAIL = "detail",
            SOURCE = "source",
            LINEAGE = "lineage",
            NATIVE = "native-details",
            CURSOR = "cursor",
            LIMIT = "limit",
            OFFSET = "offset",
            KIND = "kind",
            Q = "query";
    private static final List<String> RESOURCE_VIEWS = List.of(DETAIL, SOURCE, LINEAGE, NATIVE);
    private static final String QUERY_SCHEMA =
            """
            {"type":"object","additionalProperties":false,"required":["mode"],"properties":{
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

    /** 固定工具用途。 */
    private final Kind kind;

    /** 唯一v3客户端。 */
    private final DbtServiceClient client;

    /** 统一序列化器。 */
    private final ObjectMapper json;

    /** 服务端持久分析选择解析器。 */
    private final BuildContextResolver builds;

    /** 执行到可信会话的归属检查。 */
    private final AnalysisSessionResolver sessions;

    /** HTTP 总期限；工具预算另留上下文解析和结果组装余量。 */
    private final java.time.Duration requestTimeout;

    @Override
    public String name() {
        return TOOL_IDS.get(kind.ordinal());
    }

    @Override
    public boolean supportsIdempotency() {
        return true;
    }

    @Override
    public java.time.Duration timeout() {
        return requestTimeout.plusSeconds(15);
    }

    @Override
    public String description() {
        return switch (kind) {
            case BUILD -> "读取当前会话固定build的状态和能力；构建成功不表示已部署。";
            case CATALOG -> "搜索当前固定build的封存目录，以游标分页。";
            case RESOURCE -> "读取固定build资源详情、源码或血缘；源码按字符分页。";
            case OPTIONS -> "同步读取固定build和指标集合的合法查询选项。";
            case SUBMIT -> "同步执行固定build查询、解释、维度值或预览，直接返回本次结果；不接收SQL或目标覆盖参数。";
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
            case BUILD -> {}
            case CATALOG -> {
                text(p, Q, 1000);
                text(p, KIND, 64);
                text(p, CURSOR, 4096);
                number(p, LIMIT, 1, 200);
            }
            case RESOURCE -> {
                text(p, RESOURCE, 512);
                required.add(RESOURCE);
                text(p, VIEW, 32);
                var view = (ObjectNode) p.path(VIEW);
                view.put("default", DETAIL);
                var views = view.putArray("enum");
                RESOURCE_VIEWS.forEach(views::add);
                number(p, OFFSET, 0, 10000000);
                number(p, LIMIT, 1, 10000);
            }
            case OPTIONS -> {
                required.add(METRICS);
                var items =
                        p.putObject(METRICS)
                                .put(TYPE, ARRAY)
                                .put("minItems", 1)
                                .put("maxItems", 100);
                items.putObject("items").put(TYPE, STRING).put("maxLength", 512);
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
        // 每次调用只解析一次；解析器持久固定选择，不因新部署而改变本分析流程。
        UUID build = builds.resolve(sessions.resolveSession(context), context.executionId());
        if (build == null)
            throw new ToolBusinessException("DBT_BUILD_NOT_SELECTED", "会话未选择可用构建，不能回退到正式版本");
        Object value =
                switch (kind) {
                    case BUILD -> client.getBuild(build);
                    case CATALOG ->
                            client.catalog(
                                    build,
                                    new CatalogFilter(
                                            args.path(Q).asString(""),
                                            optional(args, KIND),
                                            optional(args, CURSOR),
                                            args.path(LIMIT).asInt(20)));
                    case RESOURCE -> resource(args, build);
                    case OPTIONS -> {
                        // 选项读取同步完成，只传指标集合，不创建任务或生成幂等键。
                        List<String> selected = new ArrayList<>();
                        args.path(METRICS).forEach(item -> selected.add(item.asString()));
                        yield client.submitOptions(build, new AvailableDimensionsRequest(selected));
                    }
                    case SUBMIT -> {
                        // 同步查询只补协议默认值，不再注入任务幂等键或结果分页参数。
                        ObjectNode request = (ObjectNode) args.deepCopy();
                        if (!request.has(LIMIT)) request.put(LIMIT, 1000);
                        for (String field : List.of(METRICS, "groupBy", "filters", "orderBy"))
                            if (!request.has(field)) request.putArray(field);
                        yield client.submitQuery(
                                build, json.treeToValue(request, QueryRequest.class));
                    }
                };
        JsonNode result = value instanceof JsonNode node ? node : json.valueToTree(value);
        // 所有分析结果必须再次核实 build 归属，不能接受其他构建的结果。
        same(build, UUID.fromString(result.path(BUILD).asString()));
        return DbtToolOutput.bounded(result, json);
    }

    private static String optional(JsonNode node, String field) {
        return node.has(field) ? node.path(field).asString() : null;
    }

    private static void same(UUID expected, UUID actual) throws ToolBusinessException {
        if (!expected.equals(actual)) throw invalid();
    }

    private static ToolBusinessException invalid() {
        return new ToolBusinessException("DBT_PROTOCOL_INVALID", "响应不属于当前固定构建");
    }

    private JsonNode resource(JsonNode args, UUID build) throws Exception {
        String resource = args.path(RESOURCE).asString();
        String view = args.path(VIEW).asString(DETAIL);
        Object value =
                switch (view) {
                    case DETAIL -> client.resource(build, resource);
                    case SOURCE -> client.resourceSource(build, resource);
                    case LINEAGE -> client.resourceLineage(build, resource);
                    case NATIVE -> client.resourceNative(build, resource);
                    default -> throw new IllegalArgumentException("未知资源视图");
                };
        ObjectNode result = json.valueToTree(value);
        if (result.has(CONTENT)) {
            String content = result.path(CONTENT).asString();
            int start = Math.min(args.path(OFFSET).asInt(0), content.length());
            int end = Math.min(start + args.path(LIMIT).asInt(8000), content.length());
            result.put(CONTENT, content.substring(start, end))
                    .put(OFFSET, start)
                    .put("totalCharacters", content.length());
            if (end < content.length()) result.put("nextOffset", end);
            else result.putNull("nextOffset");
        }
        return result;
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
