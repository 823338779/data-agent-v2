package com.datagent.agentv2.conversation.cluster;

import java.time.Instant;
import java.util.Objects;

/**
 * Redis 只转发当前回答增量，不承载持久化生命周期或敏感工具正文。
 *
 * @param tenantId 执行所属租户，用于 Redis channel 和租户隔离
 * @param conversationSessionId 增量所属会话，用于定位本地 SSE 连接
 * @param executionId 增量所属 Agent 执行
 * @param executionAttemptNumber owner fencing attempt
 * @param modelInvocationSequence 执行内模型调用序号
 * @param outputDeltaSequence 模型调用内回答增量序号
 * @param delta 回答正文片段
 * @param occurredAt 回答片段生成时间
 */
public record AgentExecutionDeltaNotification(
        String tenantId,
        String conversationSessionId,
        String executionId,
        long executionAttemptNumber,
        long modelInvocationSequence,
        long outputDeltaSequence,
        String delta,
        Instant occurredAt) {

    /** 校验跨节点通知必需的路由字段和严格为正的流坐标。 */
    public AgentExecutionDeltaNotification {
        // 路由、执行身份和正文均不可为空，否则接收端无法安全排序或投递。
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(conversationSessionId, "conversationSessionId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(occurredAt, "occurredAt");

        // 已发布增量只允许使用从 1 开始的 attempt、调用和 delta 序号。
        if (executionAttemptNumber < 1 || modelInvocationSequence < 1 || outputDeltaSequence < 1) {
            throw new IllegalArgumentException("invalid Agent execution delta notification");
        }
    }
}
