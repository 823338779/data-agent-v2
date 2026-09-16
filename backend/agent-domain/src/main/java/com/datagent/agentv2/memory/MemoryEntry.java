package com.datagent.agentv2.memory;

import java.time.LocalDateTime;
import java.util.Objects;

/** 已提交的单条个人记忆领域快照；content 始终保存用户可见原文。 */
public record MemoryEntry(
        String memoryId,
        MemoryKind kind,
        String content,
        String normalizedHash,
        int normalizationVersion,
        int charCount,
        MemorySource source,
        boolean userProtected,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime lastConfirmedAt,
        String promptVersion) {
    public MemoryEntry {
        Objects.requireNonNull(memoryId, "memoryId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(normalizedHash, "normalizedHash");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (normalizationVersion < 1 || charCount < 1 || version < 1) {
            throw new IllegalArgumentException("记忆版本和正文长度必须为正数");
        }
    }
}
