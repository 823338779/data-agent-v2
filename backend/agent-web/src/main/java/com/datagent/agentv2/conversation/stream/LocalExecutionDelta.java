package com.datagent.agentv2.conversation.stream;

import java.time.Instant;
import java.util.Objects;

/**
 * 当前进程内直接投递给 SSE 连接的回答增量。
 *
 * @param tenantId 执行的固定租户标识，用于连接路由
 * @param conversationSessionId 执行所属会话标识
 * @param executionId 增量所属执行；切换执行时不可沿用旧流游标
 * @param modelInvocationSequence 当前执行内从 1 开始的模型调用序号
 * @param outputDeltaSequence 当前模型调用内从 1 开始的连续增量序号
 * @param delta 本次新增的回答文本，不包含思考过程
 * @param occurredAt 增量产生时刻，使用 UTC Instant
 */
public record LocalExecutionDelta(
        String tenantId,
        String conversationSessionId,
        String executionId,
        long modelInvocationSequence,
        long outputDeltaSequence,
        String delta,
        Instant occurredAt) {

    public LocalExecutionDelta {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(conversationSessionId, "conversationSessionId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (modelInvocationSequence < 1L || outputDeltaSequence < 1L) {
            throw new IllegalArgumentException("invalid local execution delta");
        }
    }
}
