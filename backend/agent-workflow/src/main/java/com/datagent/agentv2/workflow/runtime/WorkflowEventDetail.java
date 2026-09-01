package com.datagent.agentv2.workflow.runtime;

import java.util.List;

/** 可公开的 Workflow 事件详情；禁止增加结果行、Prompt、凭据和原始异常。 */
public record WorkflowEventDetail(
        List<String> semanticResourceNames,
        String sql,
        Long returnedRows,
        Boolean truncated,
        Integer attempt,
        Integer maxAttempts,
        Long elapsedMillis,
        String message,
        WorkflowFailureCode failureCode) {

    public WorkflowEventDetail {
        semanticResourceNames =
                semanticResourceNames == null ? List.of() : List.copyOf(semanticResourceNames);
    }

    public static WorkflowEventDetail empty() {
        return new WorkflowEventDetail(List.of(), null, null, null, null, null, null, null, null);
    }

    public static WorkflowEventDetail message(String message) {
        return new WorkflowEventDetail(
                List.of(), null, null, null, null, null, null, message, null);
    }

    public static WorkflowEventDetail attempt(int attempt, int maxAttempts, String message) {
        return new WorkflowEventDetail(
                List.of(), null, null, null, attempt, maxAttempts, null, message, null);
    }

    public static WorkflowEventDetail sql(
            String sql, int attempt, int maxAttempts, long elapsedMillis) {
        return new WorkflowEventDetail(
                List.of(),
                sql,
                null,
                null,
                attempt,
                maxAttempts,
                elapsedMillis,
                "SQL 已通过安全校验和 StarRocks 预检",
                null);
    }

    public static WorkflowEventDetail query(long rows, boolean truncated, long elapsedMillis) {
        return new WorkflowEventDetail(
                List.of(), null, rows, truncated, null, null, elapsedMillis, "查询执行完成", null);
    }

    public static WorkflowEventDetail failure(WorkflowFailureCode code, String message) {
        return new WorkflowEventDetail(
                List.of(), null, null, null, null, null, null, message, code);
    }
}
