package com.datagent.agentv2.datasource.query;

/** 数据源错误的有限分类，隔离不稳定且可能敏感的厂商消息。 */
public enum QueryFailureCategory {
    SYNTAX_OR_SCHEMA,
    TRANSIENT_CONNECTION,
    TIMEOUT,
    RESULT_TOO_LARGE,
    FATAL
}
