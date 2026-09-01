package com.datagent.agentv2.workflow.runtime;

/** 重连时展示的任务快照；SQL 仅在 EXPLAIN 成功后出现。 */
public record WorkflowTaskSnapshot(
        WorkflowTaskRef task,
        WorkflowStep currentStep,
        String sql,
        Long returnedRows,
        Boolean truncated,
        Integer attempt,
        String message) {}
