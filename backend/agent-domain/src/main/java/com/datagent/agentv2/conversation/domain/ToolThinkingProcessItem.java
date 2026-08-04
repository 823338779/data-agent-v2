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
        /** 工具注册表中的稳定标识。 */ String toolId,
        /** 工具展示名称。 */ String toolName,
        /** 服务端工具执行标识。 */ String toolExecutionId,
        /** 模型供应商产生的工具调用标识。 */ String toolCallId,
        /** 选择该工具的模型调用序号。 */ long modelInvocationSequence,
        /** 经过安全处理并按上限持久化的工具入参。 */ BoundedProcessPayload input,
        /** 经过安全处理并按上限持久化的工具出参；非终态允许为空。 */ BoundedProcessPayload output,
        /** 工具是否成功；非终态为空。 */ Boolean success,
        /** 稳定失败错误码；成功或非终态为空。 */ String errorCode,
        /** 经过安全处理的失败信息；成功或非终态为空。 */ String errorMessage)
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
        Objects.requireNonNull(toolId, "toolId");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(toolExecutionId, "toolExecutionId");
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(input, "input");
        if (modelInvocationSequence < 1L) {
            throw new IllegalArgumentException("modelInvocationSequence must be positive");
        }

        // 工具成功、失败和非终态分别使用互斥的结果字段形状。
        if (status == ThinkingProcessStatus.SUCCEEDED && !Boolean.TRUE.equals(success)) {
            throw new IllegalArgumentException("SUCCEEDED tool process item requires success=true");
        }
        if (status == ThinkingProcessStatus.FAILED && !Boolean.FALSE.equals(success)) {
            throw new IllegalArgumentException("FAILED tool process item requires success=false");
        }
        if ((status == ThinkingProcessStatus.PREPARED || status == ThinkingProcessStatus.RUNNING)
                && (success != null || output != null || errorCode != null || errorMessage != null)) {
            throw new IllegalArgumentException("non-terminal tool process item cannot have result");
        }
    }

    @Override
    public ThinkingProcessType type() {
        return ThinkingProcessType.TOOL;
    }
}
