package com.datagent.agentv2.workflow.port;

import com.datagent.agentv2.workflow.runtime.WorkflowEvent;

/** 非持久化 Workflow 事件输出端口。 */
@FunctionalInterface
public interface WorkflowEventPort {
    void publish(WorkflowEvent event);
}
