package com.datagent.agentv2.conversation.context;

import java.util.Objects;

/** 上下文策略用于 fencing 和确定性读取的 execution 引用。 */
public record ContextExecutionRef(
        String tenantId,
        String conversationSessionId,
        String executionId,
        String ownerInstanceId,
        long executionAttempt,
        long inputUpToMessageSequence) {

    public ContextExecutionRef {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(conversationSessionId, "conversationSessionId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(ownerInstanceId, "ownerInstanceId");
        if (executionAttempt <= 0 || inputUpToMessageSequence < 0) {
            throw new IllegalArgumentException("Invalid context execution boundary");
        }
    }
}
