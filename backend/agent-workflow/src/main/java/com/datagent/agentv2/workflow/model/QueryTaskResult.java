package com.datagent.agentv2.workflow.model;

import com.datagent.agentv2.datasource.query.TabularQueryResult;

/** 一个查询任务及其终态结果。 */
public record QueryTaskResult(QueryTask task, TabularQueryResult result) {}
