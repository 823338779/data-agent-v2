package com.datagent.agentv2.workflow.port;

import com.datagent.agentv2.workflow.runtime.WorkflowRunRequest;
import java.time.Instant;

/** 在每次模型、数据库和事件副作用前检查取消与截止时间。 */
@FunctionalInterface
public interface WorkflowExecutionControlPort {
    void assertRunning(WorkflowRunRequest run, Instant workflowDeadline);
}
