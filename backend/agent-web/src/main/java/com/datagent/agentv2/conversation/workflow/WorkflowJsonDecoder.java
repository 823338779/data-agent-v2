package com.datagent.agentv2.conversation.workflow;

import com.datagent.agentv2.workflow.model.AnalysisFinding;
import com.datagent.agentv2.workflow.model.AnalysisResult;
import com.datagent.agentv2.workflow.model.EvidenceRef;
import com.datagent.agentv2.workflow.model.ExecutionRoute;
import com.datagent.agentv2.workflow.model.GeneratedSql;
import com.datagent.agentv2.workflow.model.QueryFilter;
import com.datagent.agentv2.workflow.model.QueryOrder;
import com.datagent.agentv2.workflow.model.QueryPlan;
import com.datagent.agentv2.workflow.model.QueryTask;
import com.datagent.agentv2.workflow.model.QueryTimeRange;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 严格解码 Workflow 模型 JSON；拒绝 Markdown、尾随文本和未知字段。 */
@Component
@RequiredArgsConstructor
public final class WorkflowJsonDecoder {

    private final ObjectMapper json;

    public ExecutionRoute decodeRoute(String document) {
        JsonNode root = object(document, Set.of("route"));
        return enumValue(ExecutionRoute.class, text(root, "route"), "route");
    }

    public GeneratedSql decodeSql(String document) {
        JsonNode root = object(document, Set.of("taskId", "sql"));
        return new GeneratedSql(text(root, "taskId"), text(root, "sql"));
    }

    public QueryPlan decodeQueryPlan(String document) {
        JsonNode root = object(document, Set.of("analysisGoal", "tasks"));
        List<QueryTask> tasks =
                elements(requiredArray(root, "tasks")).stream().map(this::queryTask).toList();
        return new QueryPlan(text(root, "analysisGoal"), tasks);
    }

    public AnalysisResult decodeAnalysis(String document) {
        JsonNode root = object(document, Set.of("summary", "findings", "caveats"));
        List<AnalysisFinding> findings =
                elements(requiredArray(root, "findings")).stream().map(this::finding).toList();
        return new AnalysisResult(text(root, "summary"), findings, strings(root, "caveats"));
    }

    public String encode(Object value) {
        return json.writeValueAsString(value);
    }

    private QueryTask queryTask(JsonNode node) {
        requireObject(
                node,
                Set.of(
                        "taskId",
                        "purpose",
                        "semanticResourceIds",
                        "metrics",
                        "dimensions",
                        "filters",
                        "timeRange",
                        "orderBy",
                        "limit"));
        List<QueryFilter> filters =
                elements(requiredArray(node, "filters")).stream().map(this::filter).toList();
        List<QueryOrder> orders =
                elements(requiredArray(node, "orderBy")).stream().map(this::order).toList();
        JsonNode timeNode = node.get("timeRange");
        QueryTimeRange timeRange =
                timeNode == null || timeNode.isNull() ? null : timeRange(timeNode);
        JsonNode limitNode = node.get("limit");
        Integer limit = limitNode == null || limitNode.isNull() ? null : limitNode.intValue();
        return new QueryTask(
                text(node, "taskId"),
                text(node, "purpose"),
                strings(node, "semanticResourceIds"),
                strings(node, "metrics"),
                strings(node, "dimensions"),
                filters,
                timeRange,
                orders,
                limit);
    }

    private QueryFilter filter(JsonNode node) {
        requireObject(node, Set.of("field", "operator", "values"));
        List<Object> values =
                elements(requiredArray(node, "values")).stream()
                        .map(value -> json.convertValue(value, Object.class))
                        .toList();
        return new QueryFilter(
                text(node, "field"),
                enumValue(QueryFilter.Operator.class, text(node, "operator"), "operator"),
                values);
    }

    private QueryOrder order(JsonNode node) {
        requireObject(node, Set.of("field", "direction"));
        return new QueryOrder(
                text(node, "field"),
                enumValue(QueryOrder.Direction.class, text(node, "direction"), "direction"));
    }

    private QueryTimeRange timeRange(JsonNode node) {
        requireObject(node, Set.of("field", "startInclusive", "endExclusive"));
        try {
            return new QueryTimeRange(
                    text(node, "field"),
                    Instant.parse(text(node, "startInclusive")),
                    Instant.parse(text(node, "endExclusive")));
        } catch (RuntimeException failure) {
            throw invalid("timeRange 必须使用 ISO-8601 UTC 时间");
        }
    }

    private AnalysisFinding finding(JsonNode node) {
        requireObject(node, Set.of("statement", "evidence"));
        List<EvidenceRef> evidence =
                elements(requiredArray(node, "evidence")).stream().map(this::evidence).toList();
        return new AnalysisFinding(text(node, "statement"), evidence);
    }

    private EvidenceRef evidence(JsonNode node) {
        requireObject(node, Set.of("taskId", "rowIndexes"));
        List<Integer> rows =
                elements(requiredArray(node, "rowIndexes")).stream()
                        .map(JsonNode::intValue)
                        .toList();
        return new EvidenceRef(text(node, "taskId"), rows);
    }

    private JsonNode object(String document, Set<String> allowedFields) {
        if (document == null || document.isBlank() || document.contains("```")) {
            throw invalid("模型必须返回纯 JSON 文档");
        }
        try {
            try (var parser = json.createParser(document)) {
                JsonNode root = json.readTree(parser);
                if (parser.nextToken() != null) {
                    throw invalid("模型只能返回一个 JSON 文档");
                }
                requireObject(root, allowedFields);
                return root;
            }
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid("模型返回的 JSON 无法解析");
        }
    }

    private static void requireObject(JsonNode node, Set<String> allowedFields) {
        if (node == null || !node.isObject()) throw invalid("JSON 根节点必须是对象");
        Set<String> actual = new HashSet<>();
        node.propertyStream().forEach(entry -> actual.add(entry.getKey()));
        if (!allowedFields.containsAll(actual)) throw invalid("JSON 包含未知字段");
    }

    private static JsonNode requiredArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) throw invalid(field + " 必须是数组");
        return value;
    }

    private static List<JsonNode> elements(JsonNode array) {
        return array.valueStream().toList();
    }

    private static List<String> strings(JsonNode node, String field) {
        return elements(requiredArray(node, field)).stream().map(JsonNode::textValue).toList();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.textValue().isBlank()) {
            throw invalid(field + " 必须是非空字符串");
        }
        return value.textValue();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value.toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException failure) {
            throw invalid(field + " 枚举值不合法");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
