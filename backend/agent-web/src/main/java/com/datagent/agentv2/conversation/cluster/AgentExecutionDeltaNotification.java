package com.datagent.agentv2.conversation.cluster;

import com.datagent.agentv2.conversation.model.ModelOutputDelta;
import com.datagent.agentv2.conversation.stream.ExecutionStreamCheckpointCoordinator;
import java.time.Instant;
import java.util.Objects;

/** Redis 只转发当前流增量，不承载持久化生命周期或敏感工具正文。 */
public record AgentExecutionDeltaNotification(
        ExecutionDeltaNotificationType type,
        String tenantId,
        String conversationSessionId,
        String executionId,
        long executionAttemptNumber,
        long modelInvocationSequence,
        long outputDeltaSequence,
        ExecutionDeltaType deltaType,
        String delta,
        Instant occurredAt) {

    public AgentExecutionDeltaNotification {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(conversationSessionId, "conversationSessionId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(deltaType, "deltaType");
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (executionAttemptNumber < 1 || modelInvocationSequence < 1 || outputDeltaSequence < 1) {
            throw new IllegalArgumentException("invalid Agent execution delta notification");
        }
    }

    public static AgentExecutionDeltaNotification from(
            ExecutionStreamCheckpointCoordinator.PublishedDelta published) {
        ExecutionDeltaType deltaType =
                published.kind() == ModelOutputDelta.Kind.REASONING
                        ? ExecutionDeltaType.REASONING
                        : ExecutionDeltaType.ASSISTANT;
        return new AgentExecutionDeltaNotification(
                ExecutionDeltaNotificationType.STREAM_DELTA,
                published.tenantId(),
                published.conversationSessionId(),
                published.executionId(),
                published.executionAttemptNumber(),
                published.modelInvocationSequence(),
                published.outputDeltaSequence(),
                deltaType,
                published.delta(),
                published.occurredAt());
    }
}
