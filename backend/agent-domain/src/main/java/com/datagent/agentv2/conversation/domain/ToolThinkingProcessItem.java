package com.datagent.agentv2.conversation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 一次工具调用从 prepared 到 terminal 的完整过程节点。 */
public record ToolThinkingProcessItem(
        UUID processItemId,
        long sequence,
        ThinkingProcessStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Long durationMillis,
        int schemaVersion,
        String toolName,
        String toolCallId,
        long modelInvocationSequence,
        BoundedProcessPayload input,
        BoundedProcessPayload output,
        String errorCode,
        String errorMessage)
        implements ThinkingProcessItem {

    public ToolThinkingProcessItem {
        ThinkingProcessItem.validateCommon(
                processItemId,
                sequence,
                ThinkingProcessType.TOOL,
                status,
                createdAt,
                startedAt,
                completedAt,
                durationMillis,
                schemaVersion);
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(input, "input");
        if (modelInvocationSequence < 1L) {
            throw new IllegalArgumentException("modelInvocationSequence must be positive");
        }
        if ((status == ThinkingProcessStatus.PREPARED || status == ThinkingProcessStatus.RUNNING)
                && (output != null || errorCode != null || errorMessage != null)) {
            throw new IllegalArgumentException("non-terminal tool process item cannot have result");
        }
    }

    @Override
    public ThinkingProcessType type() {
        return ThinkingProcessType.TOOL;
    }
}
