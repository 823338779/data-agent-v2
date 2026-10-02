package com.datagent.agentv2.memory;

import java.time.LocalDateTime;
import java.util.Objects;

/** 已提交的单条个人记忆领域快照；content 始终保存用户可见原文。 */
public record MemoryEntry(
        /** 条目业务标识，跨修订保持不变。 */
        String memoryId,
        /** 个人事实或协作偏好分类，决定容量配额。 */
        MemoryKind kind,
        /** 用户可见原文，不能用归一化文本替代。 */
        String content,
        /** 按 normalizationVersion 归一化后计算的去重摘要。 */
        String normalizedHash,
        /** 归一化算法版本，至少为 1，与 normalizedHash 配套使用。 */
        int normalizationVersion,
        /** 正文 Java UTF-16 代码单元数，与容量统计口径一致。 */
        int charCount,
        /** 首次写入来源，修订不改变原始来源。 */
        MemorySource source,
        /** 人工明确保护的条目，后台审查不得自主覆盖或移除。 */
        boolean userProtected,
        /** 条目 CAS 版本，自 1 起，每次修订或确认递增。 */
        long version,
        /** 首次提交时间，北京时间。 */
        LocalDateTime createdAt,
        /** 最近一次修订或确认时间，北京时间。 */
        LocalDateTime updatedAt,
        /** 最近一次证据确认时间；历史记录可以为空。 */
        LocalDateTime lastConfirmedAt,
        /** 模型写入时的提示词版本；人工路径为空。 */
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
