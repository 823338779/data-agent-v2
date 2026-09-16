package com.datagent.agentv2.memory;

import java.time.LocalDateTime;
import java.util.Objects;

/** 写入判定所需的短期证据；content 只参与当前校验，不复制进长期证据表。 */
public record MemoryEvidence(
        String evidenceId,
        Kind kind,
        String conversationId,
        String executionId,
        long sequence,
        String authorUserId,
        long messageVersion,
        String content,
        /** 可信数据库北京时间；用户消息取独立证据创建时间，UI 操作取提交事务数据库时间。 */
        LocalDateTime createdAt) {
    public enum Kind {
        USER_MESSAGE,
        UI_OPERATION,
        ASSISTANT_CONTEXT
    }

    public MemoryEvidence {
        Objects.requireNonNull(evidenceId, "evidenceId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
        if (sequence < 0 || messageVersion < 0) {
            throw new IllegalArgumentException("证据序号和版本不得为负数");
        }
    }
}
