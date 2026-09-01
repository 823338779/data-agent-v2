package com.datagent.agentv2.workflow.runtime;

import com.fasterxml.jackson.annotation.JsonValue;

/** 对外稳定错误码，不包含数据库、模型或连接实现细节。 */
public enum WorkflowFailureCode {
    SEMANTIC_RESOURCE_NOT_FOUND("semantic_resource_not_found"),
    QUERY_PLAN_INVALID("query_plan_invalid"),
    QUERY_PLAN_ATTEMPTS_EXHAUSTED("query_plan_attempts_exhausted"),
    SQL_GENERATION_INVALID("sql_generation_invalid"),
    SQL_POLICY_DENIED("sql_policy_denied"),
    SQL_EXPLAIN_FAILED("sql_explain_failed"),
    SQL_ATTEMPTS_EXHAUSTED("sql_attempts_exhausted"),
    QUERY_TIMEOUT("query_timeout"),
    QUERY_RESULT_TOO_LARGE("query_result_too_large"),
    QUERY_ENGINE_UNAVAILABLE("query_engine_unavailable"),
    ANALYSIS_INPUT_TOO_LARGE("analysis_input_too_large"),
    ANALYSIS_FAILED("analysis_failed"),
    WORKFLOW_CANCELLED("workflow_cancelled"),
    WORKFLOW_DEADLINE_EXCEEDED("workflow_deadline_exceeded"),
    WORKFLOW_INTERNAL_ERROR("workflow_internal_error");

    private final String value;

    WorkflowFailureCode(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
