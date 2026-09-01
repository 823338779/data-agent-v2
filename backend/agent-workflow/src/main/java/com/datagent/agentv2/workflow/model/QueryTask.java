package com.datagent.agentv2.workflow.model;

import java.util.List;

/** 查询计划中的顺序任务；不含 SQL、依赖图或运行时数据源标识。 */
public record QueryTask(
        String taskId,
        String purpose,
        List<String> semanticResourceIds,
        List<String> metrics,
        List<String> dimensions,
        List<QueryFilter> filters,
        QueryTimeRange timeRange,
        List<QueryOrder> orderBy,
        Integer limit) {

    public QueryTask {
        semanticResourceIds = copy(semanticResourceIds);
        metrics = copy(metrics);
        dimensions = copy(dimensions);
        filters = filters == null ? List.of() : List.copyOf(filters);
        orderBy = orderBy == null ? List.of() : List.copyOf(orderBy);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
