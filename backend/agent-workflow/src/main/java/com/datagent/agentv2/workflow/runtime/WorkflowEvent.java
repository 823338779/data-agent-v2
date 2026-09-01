package com.datagent.agentv2.workflow.runtime;

import java.time.Instant;

/** 一条按 Run 单调递增的内存 Workflow 事件。 */
public record WorkflowEvent(
        String tenantId,
        String conversationSessionId,
        String executionId,
        String workflowId,
        long sequence,
        WorkflowEventType eventType,
        WorkflowStep step,
        WorkflowStatus status,
        WorkflowTaskRef task,
        WorkflowEventDetail detail,
        Instant occurredAt) {}
