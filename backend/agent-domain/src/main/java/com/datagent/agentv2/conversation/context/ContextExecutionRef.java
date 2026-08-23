package com.datagent.agentv2.conversation.context;

import java.util.Objects;

/**
 * 上下文策略用于确定性读取的本机 execution 引用。
 *
 * @param tenantId 执行所属租户，限定上下文数据边界
 * @param conversationSessionId 执行所属会话
 * @param executionId 当前执行业务标识，用于取消与截止时间校验
 * @param inputUpToMessageSequence 上下文允许读取的最高消息序号，必须为非负数
 */
public record ContextExecutionRef(
        String tenantId,
        String conversationSessionId,
        String executionId,
        long inputUpToMessageSequence) {

    public ContextExecutionRef {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(conversationSessionId, "conversationSessionId");
        Objects.requireNonNull(executionId, "executionId");
        if (inputUpToMessageSequence < 0L) {
            throw new IllegalArgumentException("inputUpToMessageSequence must not be negative");
        }
    }
}
