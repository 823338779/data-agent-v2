package com.datagent.agentv2.workflow.runtime;

import com.datagent.agentv2.datasource.query.QueryAccessScope;
import com.datagent.agentv2.datasource.semantic.SemanticDataset;
import com.datagent.agentv2.datasource.semantic.SemanticField;
import com.datagent.agentv2.datasource.semantic.SemanticMetric;
import com.datagent.agentv2.datasource.semantic.SemanticRelationship;
import com.datagent.agentv2.datasource.semantic.SemanticResourceSet;
import com.datagent.agentv2.workflow.config.DataAnalysisWorkflowProperties;
import com.datagent.agentv2.workflow.model.QueryFilter;
import com.datagent.agentv2.workflow.model.QueryOrder;
import com.datagent.agentv2.workflow.model.QueryPlan;
import com.datagent.agentv2.workflow.model.QueryTask;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 对 QueryPlan 进行确定性校验，并为每个任务收敛 SQL 物理访问范围。 */
public final class QueryPlanValidator {

    public ValidatedQueryPlan validate(
            QueryPlan plan,
            SemanticResourceSet resources,
            DataAnalysisWorkflowProperties properties,
            int datasourceMaxRows) {
        if (plan == null || blank(plan.analysisGoal())) {
            throw invalid("analysisGoal 不能为空");
        }
        if (plan.tasks().isEmpty() || plan.tasks().size() > properties.maxQueryTasks()) {
            throw invalid("QueryTask 数量超出允许范围");
        }
        ResourceIndex index = ResourceIndex.from(resources);
        Set<String> taskIds = new HashSet<>();
        Map<String, QueryAccessScope> scopes = new LinkedHashMap<>();
        for (QueryTask task : plan.tasks()) {
            if (blank(task.taskId()) || !taskIds.add(task.taskId())) {
                throw invalid("QueryTask taskId 不能为空且必须唯一");
            }
            if (blank(task.purpose())) {
                throw invalid("QueryTask purpose 不能为空");
            }
            if (task.semanticResourceIds().isEmpty()) {
                throw invalid("QueryTask 必须引用语义资源: " + task.taskId());
            }
            task.semanticResourceIds().forEach(id -> requireResource(index, id));
            task.metrics().forEach(id -> requireMetric(index, id));
            task.dimensions().forEach(id -> requireField(index, id));
            task.filters().forEach(filter -> validateFilter(index, filter));
            task.orderBy().forEach(order -> validateOrder(index, order));
            if (task.timeRange() != null) {
                requireField(index, task.timeRange().field());
                if (task.timeRange().startInclusive() == null
                        || task.timeRange().endExclusive() == null
                        || !task.timeRange()
                                .startInclusive()
                                .isBefore(task.timeRange().endExclusive())) {
                    throw invalid("时间范围必须是有效半开区间");
                }
            }
            int limit = task.limit() == null ? datasourceMaxRows : task.limit();
            if (limit <= 0) throw invalid("QueryTask limit 必须大于 0");
            scopes.put(task.taskId(), buildScope(task, index, Math.min(limit, datasourceMaxRows)));
        }
        return new ValidatedQueryPlan(plan, scopes);
    }

    private static QueryAccessScope buildScope(QueryTask task, ResourceIndex index, int maxRows) {
        Set<String> datasetIds = new LinkedHashSet<>();
        Set<FieldLocation> fields = new LinkedHashSet<>();
        Set<String> selectedRelationshipIds = new LinkedHashSet<>();
        for (String id : task.semanticResourceIds()) {
            if (index.datasets.containsKey(id)) datasetIds.add(id);
            FieldLocation field = index.fields.get(id);
            if (field != null) {
                fields.add(field);
                datasetIds.add(field.dataset.id());
            }
            SemanticMetric metric = index.metrics.get(id);
            if (metric != null) addMetric(metric, index, datasetIds, fields);
            if (index.relationships.containsKey(id)) selectedRelationshipIds.add(id);
        }
        task.metrics().forEach(id -> addMetric(index.metrics.get(id), index, datasetIds, fields));
        task.dimensions().forEach(id -> addField(index, id, datasetIds, fields));
        task.filters().forEach(filter -> addField(index, filter.field(), datasetIds, fields));
        task.orderBy().forEach(order -> addField(index, order.field(), datasetIds, fields));
        if (task.timeRange() != null) addField(index, task.timeRange().field(), datasetIds, fields);

        Set<QueryAccessScope.AllowedJoin> joins = new LinkedHashSet<>();
        for (String relationshipId : selectedRelationshipIds) {
            SemanticRelationship relationship = index.relationships.get(relationshipId);
            SemanticDataset left = index.datasets.get(relationship.leftDataset());
            SemanticDataset right = index.datasets.get(relationship.rightDataset());
            datasetIds.add(left.id());
            datasetIds.add(right.id());
            SemanticField leftField = left.fieldsById().get(relationship.leftField());
            SemanticField rightField = right.fieldsById().get(relationship.rightField());
            fields.add(new FieldLocation(left, leftField));
            fields.add(new FieldLocation(right, rightField));
            joins.add(
                    new QueryAccessScope.AllowedJoin(
                            left.table(), leftField.column(), right.table(), rightField.column()));
        }
        if (datasetIds.size() > 1 && joins.isEmpty()) {
            throw invalid("跨数据集查询必须显式引用允许的 relationship");
        }
        Map<String, Set<String>> columnsByTable = new LinkedHashMap<>();
        for (FieldLocation field : fields) {
            columnsByTable
                    .computeIfAbsent(field.dataset.table(), ignored -> new LinkedHashSet<>())
                    .add(field.field.column());
        }
        Set<String> tables =
                datasetIds.stream()
                        .map(id -> index.datasets.get(id).table())
                        .collect(java.util.stream.Collectors.toSet());
        return new QueryAccessScope(tables, columnsByTable, joins, maxRows);
    }

    private static void addMetric(
            SemanticMetric metric,
            ResourceIndex index,
            Set<String> datasetIds,
            Set<FieldLocation> fields) {
        SemanticDataset dataset = index.datasets.get(metric.dataset());
        datasetIds.add(dataset.id());
        fields.add(new FieldLocation(dataset, dataset.fieldsById().get(metric.field())));
        metric.filters()
                .forEach(
                        filter ->
                                fields.add(
                                        new FieldLocation(
                                                dataset,
                                                dataset.fieldsById().get(filter.field()))));
    }

    private static void addField(
            ResourceIndex index, String id, Set<String> datasetIds, Set<FieldLocation> fields) {
        FieldLocation field = index.fields.get(id);
        datasetIds.add(field.dataset.id());
        fields.add(field);
    }

    private static void validateFilter(ResourceIndex index, QueryFilter filter) {
        requireField(index, filter.field());
        if (filter.operator() == null) throw invalid("过滤操作符不能为空");
        int count = filter.values().size();
        boolean nullOperator =
                filter.operator() == QueryFilter.Operator.IS_NULL
                        || filter.operator() == QueryFilter.Operator.IS_NOT_NULL;
        boolean setOperator =
                filter.operator() == QueryFilter.Operator.IN
                        || filter.operator() == QueryFilter.Operator.NOT_IN;
        if ((nullOperator && count != 0)
                || (setOperator && count == 0)
                || (!nullOperator && !setOperator && count != 1)) {
            throw invalid("过滤值数量与操作符不匹配");
        }
    }

    private static void validateOrder(ResourceIndex index, QueryOrder order) {
        requireField(index, order.field());
        if (order.direction() == null) throw invalid("排序方向不能为空");
    }

    private static void requireResource(ResourceIndex index, String id) {
        if (!index.allIds.contains(id)) throw invalid("引用了未召回的语义资源: " + id);
    }

    private static void requireMetric(ResourceIndex index, String id) {
        if (!index.metrics.containsKey(id)) throw invalid("引用了未召回的指标: " + id);
    }

    private static void requireField(ResourceIndex index, String id) {
        if (!index.fields.containsKey(id)) throw invalid("引用了未召回或有歧义的字段: " + id);
    }

    private static WorkflowValidationException invalid(String message) {
        return new WorkflowValidationException(WorkflowFailureCode.QUERY_PLAN_INVALID, message);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record ValidatedQueryPlan(QueryPlan plan, Map<String, QueryAccessScope> accessScopes) {
        public ValidatedQueryPlan {
            accessScopes = Map.copyOf(accessScopes);
        }
    }

    private record FieldLocation(SemanticDataset dataset, SemanticField field) {}

    private static final class ResourceIndex {
        private final Map<String, SemanticDataset> datasets = new HashMap<>();
        private final Map<String, FieldLocation> fields = new HashMap<>();
        private final Set<String> ambiguousFields = new HashSet<>();
        private final Map<String, SemanticMetric> metrics = new HashMap<>();
        private final Map<String, SemanticRelationship> relationships = new HashMap<>();
        private final Set<String> allIds = new HashSet<>();

        static ResourceIndex from(SemanticResourceSet resources) {
            ResourceIndex index = new ResourceIndex();
            resources
                    .datasets()
                    .forEach(
                            dataset -> {
                                index.datasets.put(dataset.id(), dataset);
                                index.allIds.add(dataset.id());
                            });
            resources
                    .fields()
                    .forEach(
                            ref -> {
                                SemanticDataset dataset = index.datasets.get(ref.datasetId());
                                if (dataset == null)
                                    throw invalid("字段缺少所属数据集: " + ref.field().id());
                                if (index.fields.putIfAbsent(
                                                ref.field().id(),
                                                new FieldLocation(dataset, ref.field()))
                                        != null) {
                                    index.ambiguousFields.add(ref.field().id());
                                }
                                index.allIds.add(ref.field().id());
                            });
            index.ambiguousFields.forEach(index.fields::remove);
            resources
                    .metrics()
                    .forEach(
                            metric -> {
                                index.metrics.put(metric.id(), metric);
                                index.allIds.add(metric.id());
                            });
            resources
                    .relationships()
                    .forEach(
                            relationship -> {
                                index.relationships.put(relationship.id(), relationship);
                                index.allIds.add(relationship.id());
                            });
            return index;
        }
    }
}
