package com.datagent.agentv2.datasource.query;

/** 向 Workflow 暴露的安全查询失败，只携带稳定分类。 */
public final class QueryFailureException extends RuntimeException {

    private final QueryFailureCategory category;

    public QueryFailureException(QueryFailureCategory category) {
        super("StarRocks 查询失败: " + category.name().toLowerCase());
        this.category = category;
    }

    QueryFailureException(QueryFailureCategory category, Throwable cause) {
        super("StarRocks 查询失败: " + category.name().toLowerCase(), cause);
        this.category = category;
    }

    public QueryFailureCategory category() {
        return category;
    }
}
