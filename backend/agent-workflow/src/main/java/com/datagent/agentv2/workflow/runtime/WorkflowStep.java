package com.datagent.agentv2.workflow.runtime;

import com.fasterxml.jackson.annotation.JsonValue;

/** 数据分析 Workflow 的固定节点集合，不支持运行时扩展。 */
public enum WorkflowStep {
    DISCOVER_SEMANTIC_RESOURCES("discover_semantic_resources"),
    BUILD_QUERY_PLAN("build_query_plan"),
    VALIDATE_QUERY_PLAN("validate_query_plan"),
    GENERATE_SQL("generate_sql"),
    VALIDATE_SQL("validate_sql"),
    EXPLAIN_SQL("explain_sql"),
    EXECUTE_QUERY("execute_query"),
    VERIFY_RESULTS("verify_results"),
    ANALYZE_RESULTS("analyze_results"),
    FINALIZE_RESPONSE("finalize_response");

    private final String value;

    WorkflowStep(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
