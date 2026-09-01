package com.datagent.agentv2.workflow.runtime;

/** SSE 中的任务定位信息；index 从 1 开始。 */
public record WorkflowTaskRef(String taskId, String purpose, int index, int total) {}
