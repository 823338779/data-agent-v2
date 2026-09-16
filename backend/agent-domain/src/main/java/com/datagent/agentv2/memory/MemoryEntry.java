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

    /** 新增统一采用版本 1；来源由可信调用模式推导，正文始终保留输入原文。 */
    public static MemoryEntry composeFromNewMemory(
            String id,
            MemoryKind kind,
            String content,
            String hash,
            MemorySource source,
            boolean userProtected,
            LocalDateTime now) {
        return composeFromNewMemory(id, kind, content, hash, source, userProtected, now, null);
    }

    /** 模型新建记录实际提示词版本；人工路径由调用方传空。 */
    public static MemoryEntry composeFromNewMemory(
            String id,
            MemoryKind kind,
            String content,
            String hash,
            MemorySource source,
            boolean userProtected,
            LocalDateTime now,
            String promptVersion) {
        return new MemoryEntry(
                id,
                kind,
                content,
                hash,
                1,
                content.length(),
                source,
                userProtected,
                1,
                now,
                now,
                now,
                promptVersion);
    }

    /** 修订或人工明确确认均推进条目版本；原始来源、创建时间及提示词版本不会被改写。 */
    public MemoryEntry revised(
            String content, String hash, boolean explicitlyRequested, LocalDateTime now) {
        return new MemoryEntry(
                memoryId,
                kind,
                content,
                hash,
                normalizationVersion,
                content.length(),
                source,
                userProtected || explicitlyRequested,
                version + 1,
                createdAt,
                now,
                now,
                promptVersion);
    }
}
