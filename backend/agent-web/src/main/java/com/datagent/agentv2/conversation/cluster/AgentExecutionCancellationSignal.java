package com.datagent.agentv2.conversation.cluster;

import java.time.Instant;
import java.util.Objects;

/** 跨实例取消唤醒信号；数据库中的 Agent 执行状态仍是唯一控制事实来源。 */
public record AgentExecutionCancellationSignal(
        String notificationMessageId,
        String tenantId,
        String executionId,
        String ownerInstanceId,
        long executionAttemptNumber,
        long controlVersion,
        Instant requestedAt,
        ExecutionControlType type) {

    public AgentExecutionCancellationSignal {
        Objects.requireNonNull(notificationMessageId, "notificationMessageId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(ownerInstanceId, "ownerInstanceId");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(type, "type");
        if (executionAttemptNumber < 1 || controlVersion < 1) {
            throw new IllegalArgumentException("invalid Agent execution cancellation signal");
        }
    }
}
