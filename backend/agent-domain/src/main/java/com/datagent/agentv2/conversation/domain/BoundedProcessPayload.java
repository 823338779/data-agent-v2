package com.datagent.agentv2.conversation.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/** 工具结构化载荷在数据库中的完整值或有界预览。 */
public record BoundedProcessPayload(
        /** 未截断时保存的结构化 JSON 值。 */
        Object value,
        /** 截断时保存的 UTF-8 安全文本预览。 */
        String preview,
        /** 数据库值是否发生截断。 */
        boolean truncated,
        /** 安全处理后完整规范 JSON 的 UTF-8 字节数。 */
        long originalSizeBytes,
        /** 安全处理后完整规范 JSON 的 SHA-256。 */
        String sha256,
        /** 载荷内容类型，首版固定为 application/json。 */
        String contentType,
        /** 新策略完整安全原文的引用；旧数据与未启用策略时为空。 */
        @com.fasterxml.jackson.annotation.JsonInclude(
                        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                com.datagent.agentv2.context.hierarchical.domain.ContextPayloadReference
                        reference) {
    public BoundedProcessPayload(
            Object value,
            String preview,
            boolean truncated,
            long originalSizeBytes,
            String sha256,
            String contentType) {
        this(value, preview, truncated, originalSizeBytes, sha256, contentType, null);
    }

    /** 原预览和完整值保持不变，只追加已归档凭据。 */
    public BoundedProcessPayload withReference(
            com.datagent.agentv2.context.hierarchical.domain.ContextPayloadReference reference) {
        return new BoundedProcessPayload(
                value, preview, truncated, originalSizeBytes, sha256, contentType, reference);
    }

    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    public BoundedProcessPayload {
        if (originalSizeBytes < 0L) {
            throw new IllegalArgumentException("originalSizeBytes must not be negative");
        }
        if (!SHA256_PATTERN.matcher(Objects.requireNonNull(sha256, "sha256")).matches()) {
            throw new IllegalArgumentException("sha256 must be lowercase hexadecimal");
        }
        Objects.requireNonNull(contentType, "contentType");
        if (truncated) {
            if (value != null || preview == null) {
                throw new IllegalArgumentException("truncated payload requires preview only");
            }
        } else if (value == null || preview != null) {
            throw new IllegalArgumentException("complete payload requires value only");
        }
    }
}
