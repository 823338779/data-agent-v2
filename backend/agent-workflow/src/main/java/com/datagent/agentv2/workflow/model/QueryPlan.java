package com.datagent.agentv2.workflow.model;

import java.util.List;

/** 模型输出的结构化查询计划，是领域中间表示而不是可执行 DSL。 */
public record QueryPlan(String analysisGoal, List<QueryTask> tasks) {
    public QueryPlan {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }
}
