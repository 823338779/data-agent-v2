package com.datagent.agentv2.conversation.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 与一条 assistant 正文绑定的强类型过程节点。 */
public sealed interface ThinkingProcessItem
        permits ModelThinkingProcessItem, ToolThinkingProcessItem {

    /** 首版过程文档版本。 */
    int CURRENT_SCHEMA_VERSION = 1;

    /** 节点全局唯一标识。 */
    UUID processItemId();

    /** 节点在当前消息内的单调递增序号。 */
    long sequence();

    /** 节点的多态类型。 */
    ThinkingProcessType type();

    /** 以 JavaBean 属性公开节点类型，确保会话 HTTP/SSE 投影包含稳定的 type 判别字段。 */
    default ThinkingProcessType getType() {
        return type();
    }

    /** 节点当前生命周期状态。 */
    ThinkingProcessStatus status();

    /** 节点被服务端创建的时间。 */
    Instant createdAt();

    /** 节点实际开始处理的时间；prepared 工具允许为空。 */
    Instant startedAt();

    /** 节点完成或失败的时间；非终态为空。 */
    Instant completedAt();

    /** 实际处理耗时；未开始或非终态为空。 */
    Long durationMillis();

    /** 节点文档版本。 */
    int schemaVersion();

    /** 校验所有节点共享的身份、版本、状态和时间约束。 */
    static void validateCommon(
            UUID processItemId,
            long sequence,
            ThinkingProcessType type,
            ThinkingProcessStatus status,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            Long durationMillis,
            int schemaVersion) {
        Objects.requireNonNull(processItemId, "processItemId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (sequence < 1L) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + schemaVersion);
        }
        if (startedAt != null && startedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("startedAt must not be before createdAt");
        }

        // 非终态不得携带完成时间或耗时，终态必须携带完成时间。
        boolean terminal =
                status == ThinkingProcessStatus.SUCCEEDED || status == ThinkingProcessStatus.FAILED;
        if (!terminal && (completedAt != null || durationMillis != null)) {
            throw new IllegalArgumentException("non-terminal process item cannot be completed");
        }
        if (terminal && completedAt == null) {
            throw new IllegalArgumentException("terminal process item requires completedAt");
        }
        if (completedAt != null && completedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("completedAt must not be before createdAt");
        }

        // 已经实际开始的终态节点必须使用墙钟差值作为非负耗时。
        if (terminal && startedAt != null) {
            long expected = Math.max(0L, Duration.between(startedAt, completedAt).toMillis());
            if (!Objects.equals(durationMillis, expected)) {
                throw new IllegalArgumentException("durationMillis does not match timestamps");
            }
        }
        if (startedAt == null && durationMillis != null) {
            throw new IllegalArgumentException("durationMillis requires startedAt");
        }
    }
}
