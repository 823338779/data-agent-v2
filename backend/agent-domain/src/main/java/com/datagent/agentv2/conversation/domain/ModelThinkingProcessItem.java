package com.datagent.agentv2.conversation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 一次模型调用产生的可展示思考片段。 */
public record ModelThinkingProcessItem(
        UUID processItemId,
        long sequence,
        ThinkingProcessStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Long durationMillis,
        int schemaVersion,
        /** 数据库保存的完整思考内容或有界预览。 */
        String content,
        /** 思考内容是否在数据库中发生截断。 */
        boolean contentTruncated,
        /** 安全处理后完整思考内容的 UTF-8 字节数。 */
        long originalContentSizeBytes,
        /** 安全处理后完整思考内容的 SHA-256。 */
        String contentSha256,
        /** 产生该片段的模型调用序号。 */
        long modelInvocationSequence,
        /** 模型供应商标识。 */
        String modelProvider,
        /** 模型名称。 */
        String modelName)
        implements ThinkingProcessItem {

    public ModelThinkingProcessItem {
        ThinkingProcessItem.validateCommon(
                processItemId,
                sequence,
                ThinkingProcessType.THINKING,
                status,
                createdAt,
                startedAt,
                completedAt,
                durationMillis,
                schemaVersion);
        if (status == ThinkingProcessStatus.PREPARED) {
            throw new IllegalArgumentException("THINKING process item cannot be PREPARED");
        }
        if (startedAt == null) {
            throw new IllegalArgumentException("THINKING process item requires startedAt");
        }
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(contentSha256, "contentSha256");
        Objects.requireNonNull(modelProvider, "modelProvider");
        Objects.requireNonNull(modelName, "modelName");
        if (originalContentSizeBytes < 0L || modelInvocationSequence < 1L) {
            throw new IllegalArgumentException("THINKING size and model sequence must be valid");
        }
    }

    @Override
    public ThinkingProcessType type() {
        return ThinkingProcessType.THINKING;
    }
}
