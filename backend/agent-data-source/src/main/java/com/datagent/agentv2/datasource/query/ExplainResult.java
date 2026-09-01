package com.datagent.agentv2.datasource.query;

import java.util.List;

/** StarRocks 对已验证 SQL 返回的只读执行计划文本。 */
public record ExplainResult(List<String> lines, long elapsedMillis) {
    public ExplainResult {
        lines = List.copyOf(lines);
    }
}
