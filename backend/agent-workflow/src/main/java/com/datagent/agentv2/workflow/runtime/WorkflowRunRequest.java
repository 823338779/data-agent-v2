package com.datagent.agentv2.workflow.runtime;

import java.time.Instant;
import java.util.List;

/** 从现有 execution 构建的一次数据 Workflow 输入。 */
public record WorkflowRunRequest(
        String tenantId,
        String conversationSessionId,
        String executionId,
        long inputUpToMessageSequence,
        String question,
        List<String> recentTurns,
        Instant executionDeadlineAt) {
    public WorkflowRunRequest {
        recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
    }
}
