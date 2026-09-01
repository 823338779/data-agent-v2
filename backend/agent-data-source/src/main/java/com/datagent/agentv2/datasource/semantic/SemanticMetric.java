package com.datagent.agentv2.datasource.semantic;

import java.util.List;

/** 业务指标口径；聚合函数和固定过滤均来自受信任 YAML。 */
public record SemanticMetric(
        String id,
        String name,
        String dataset,
        String description,
        Aggregation aggregation,
        String field,
        List<MetricFilter> filters,
        String unit,
        List<String> synonyms) {

    public SemanticMetric {
        filters = filters == null ? List.of() : List.copyOf(filters);
        synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
    }

    public enum Aggregation {
        SUM,
        COUNT,
        COUNT_DISTINCT,
        AVG,
        MIN,
        MAX
    }

    /** 指标内置过滤条件，不允许携带任意 SQL 表达式。 */
    public record MetricFilter(String field, String operator, Object value) {}
}
