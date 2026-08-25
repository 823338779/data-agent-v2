package com.datagent.agentv2.conversation.domain;

import java.time.Instant;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 一次用户请求触发的本机 Agent 执行。 */
@Getter
@Setter
@NoArgsConstructor
public final class AgentExecution {

    /** 执行业务唯一标识，也是当前进程内任务注册键。 */
    private String executionId;

    /** 执行所属会话业务标识。 */
    private String conversationSessionId;

    /** 触发本次执行的用户消息标识。 */
    private String triggeringConversationMessageId;

    /** 当前持久状态：running、done、cancelled 或 failed。 */
    private String status;

    /** 幂等请求类型；当前固定为消息提交。 */
    private String requestType;

    /** 调用方幂等键，在同一会话和请求类型内唯一。 */
    private String requestIdempotencyKey;

    /** 规范化请求负载的 SHA-256，用于发现同键不同载荷。 */
    private String requestPayloadHash;

    /** 失败分类码；未失败时为空。 */
    private String failureCode;

    /** 可安全记录的失败摘要，不得包含凭据或完整敏感输入。 */
    private String failureDetail;

    /** 执行硬截止时刻，超过后不得继续产生模型、工具或压缩副作用。 */
    private Instant hardDeadlineAt;

    /** 执行状态迁移使用的乐观锁版本。 */
    private Long version;

    /** 创建事务提交后将立即在本机启动的执行。 */
    public static AgentExecution running(
            String executionId,
            String conversationSessionId,
            String triggeringMessageId,
            String requestType,
            String idempotencyKey,
            String payloadHash,
            Instant hardDeadlineAt) {
        AgentExecution execution = new AgentExecution();
        execution.executionId = requireText(executionId, "executionId");
        execution.conversationSessionId =
                requireText(conversationSessionId, "conversationSessionId");
        execution.triggeringConversationMessageId =
                requireText(triggeringMessageId, "triggeringMessageId");
        execution.status = "running";
        execution.requestType = requireText(requestType, "requestType");
        execution.requestIdempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        execution.requestPayloadHash = requireText(payloadHash, "payloadHash");
        execution.hardDeadlineAt = Objects.requireNonNull(hardDeadlineAt, "hardDeadlineAt");
        execution.version = 0L;
        return execution;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
