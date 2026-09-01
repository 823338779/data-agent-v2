package com.datagent.agentv2.workflow.model;

import java.util.List;

/** 结构化过滤条件；值只作为 SQL 生成依据，不能携带 SQL 片段。 */
public record QueryFilter(String field, Operator operator, List<Object> values) {
    public QueryFilter {
        values = values == null ? List.of() : List.copyOf(values);
    }

    public enum Operator {
        EQ,
        NE,
        GT,
        GTE,
        LT,
        LTE,
        IN,
        NOT_IN,
        IS_NULL,
        IS_NOT_NULL
    }
}
