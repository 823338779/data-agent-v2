package com.datagent.agentv2.memory;

import java.time.LocalDateTime;
import java.util.Objects;

/** 物理删除后的精确指纹抑制记录，用于阻止旧证据跨分类恢复正文。 */
public record MemorySuppression(
        int normalizationVersion,
        String normalizedHash,
        long deletedRevision,
        LocalDateTime deletedAt,
        String deleteOperationId) {
    public MemorySuppression {
        Objects.requireNonNull(normalizedHash, "normalizedHash");
        Objects.requireNonNull(deletedAt, "deletedAt");
        Objects.requireNonNull(deleteOperationId, "deleteOperationId");
        if (normalizationVersion < 1 || deletedRevision < 1) {
            throw new IllegalArgumentException("抑制版本必须为正数");
        }
    }
}
