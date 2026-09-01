package com.datagent.agentv2.datasource.query;

/** 查询结果列的稳定元数据。 */
public record ResultColumn(String name, String jdbcType) {}
