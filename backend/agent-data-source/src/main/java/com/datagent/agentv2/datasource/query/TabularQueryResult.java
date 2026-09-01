package com.datagent.agentv2.datasource.query;

import java.util.List;

/** 内存中的有界表格结果；不会由数据源模块写入日志或持久化。 */
public record TabularQueryResult(
        List<ResultColumn> columns,
        List<List<Object>> rows,
        boolean truncated,
        long returnedRowCount,
        long elapsedMillis) {

    public TabularQueryResult {
        columns = List.copyOf(columns);
        rows = rows.stream().map(List::copyOf).toList();
    }
}
