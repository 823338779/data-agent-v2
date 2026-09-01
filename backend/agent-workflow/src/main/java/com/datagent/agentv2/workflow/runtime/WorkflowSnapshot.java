package com.datagent.agentv2.workflow.runtime;

import java.time.Instant;
import java.util.List;

/** 仅保存在进程内的权威展示快照。 */
public record WorkflowSnapshot(
        String workflowId,
        String executionId,
        long latestSequence,
        WorkflowStatus status,
        WorkflowStep currentStep,
        List<WorkflowTaskSnapshot> tasks,
        Instant startedAt,
        Instant updatedAt) {

    public WorkflowSnapshot {
        tasks = List.copyOf(tasks);
    }
}
