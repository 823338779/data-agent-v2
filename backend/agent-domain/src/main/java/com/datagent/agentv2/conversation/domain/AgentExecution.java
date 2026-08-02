package com.datagent.agentv2.conversation.domain;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 一次 Agent 执行及其调度、所有权和终态信息。 */
@Getter
@Setter
@NoArgsConstructor
public final class AgentExecution {

    /** Agent 执行业务唯一标识。 */
    private String executionId;

    /** 执行所属会话标识。 */
    private String conversationSessionId;

    /** 触发本次执行的用户消息标识。 */
    private String triggeringConversationMessageId;

    /** 执行生命周期状态。 */
    private String status;

    /** 当前执行阶段。 */
    private String phase;

    /** 当前执行尝试序号。 */
    private Long executionAttemptNumber;

    /** 提交执行的用户标识。 */
    private String ownerUserId;

    /** 当前持有执行权的服务实例标识。 */
    private String ownerInstanceId;

    /** 接入请求链路追踪标识。 */
    private String requestId;

    /** 执行进入队列的时间。 */
    private Instant queuedAt;

    /** 当前 owner 的 lease 到期时间。 */
    private Instant leaseExpiresAt;

    /** 当前 owner 最近一次续租时间。 */
    private Instant heartbeatAt;

    /** 异步取消请求时间。 */
    private Instant cancellationRequestedAt;

    /** retry 来源执行标识。 */
    private String retryOfExecutionId;

    /** lease 失效后的接管次数。 */
    private Integer recoveryAttemptCount;

    /** 幂等请求类型。 */
    private String requestType;

    /** 客户端幂等键。 */
    private String requestIdempotencyKey;

    /** 规范化请求负载摘要。 */
    private String requestPayloadHash;

    /** 累计活跃执行预算，单位毫秒。 */
    private Long executionBudgetMs;

    /** 已结算的活跃执行时长，单位毫秒。 */
    private Long activeExecutionMs;

    /** 当前活跃执行片段起点。 */
    private Instant activeSegmentStartedAt;

    /** 执行首次开始时间。 */
    private Instant startedAt;

    /** 执行正常完成或取消时间。 */
    private Instant completedAt;

    /** 执行失败时间。 */
    private Instant failedAt;

    /** 稳定失败代码。 */
    private String failureCode;

    /** 面向诊断的失败详情。 */
    private String failureDetail;

    /** 当前活跃片段硬截止时间。 */
    private Instant hardDeadlineAt;

    /** 执行乐观锁版本。 */
    private Long version;

    /** 组装由用户消息触发的排队执行。 */
    public static AgentExecution queued(
            String executionId,
            String conversationSessionId,
            String triggeringMessageId,
            String ownerUserId,
            String requestId,
            Instant queuedAt,
            String requestType,
            String idempotencyKey,
            String payloadHash,
            long executionBudgetMs,
            String status,
            String phase) {
        AgentExecution execution =
                queuedBase(
                        executionId,
                        conversationSessionId,
                        triggeringMessageId,
                        ownerUserId,
                        requestId,
                        queuedAt,
                        requestType,
                        idempotencyKey,
                        payloadHash,
                        executionBudgetMs,
                        status,
                        phase);
        return execution;
    }

    /** 组装终态执行的重试任务，并保留原始消息和预算。 */
    public static AgentExecution retry(
            String executionId,
            AgentExecution original,
            String requestId,
            Instant queuedAt,
            String requestType,
            String idempotencyKey,
            String payloadHash,
            String status,
            String phase) {
        AgentExecution execution =
                queuedBase(
                        executionId,
                        original.conversationSessionId,
                        original.triggeringConversationMessageId,
                        original.ownerUserId,
                        requestId,
                        queuedAt,
                        requestType,
                        idempotencyKey,
                        payloadHash,
                        original.executionBudgetMs,
                        status,
                        phase);
        execution.retryOfExecutionId = original.executionId;
        return execution;
    }

    /** 记录首次取消请求；重复请求保持原时间。 */
    public void requestCancellation(Instant requestedAt) {
        if (cancellationRequestedAt == null) {
            cancellationRequestedAt = requestedAt;
        }
    }

    /** 将尚未运行的执行直接转为取消终态，并清理所有权和计时字段。 */
    public void cancelDirectly(String cancelledStatus, Instant completedAt) {
        status = cancelledStatus;
        this.completedAt = completedAt;
        ownerInstanceId = null;
        activeSegmentStartedAt = null;
        heartbeatAt = null;
        leaseExpiresAt = null;
        hardDeadlineAt = null;
    }

    private static AgentExecution queuedBase(
            String executionId,
            String conversationSessionId,
            String triggeringMessageId,
            String ownerUserId,
            String requestId,
            Instant queuedAt,
            String requestType,
            String idempotencyKey,
            String payloadHash,
            long executionBudgetMs,
            String status,
            String phase) {
        AgentExecution execution = new AgentExecution();
        execution.executionId = executionId;
        execution.conversationSessionId = conversationSessionId;
        execution.triggeringConversationMessageId = triggeringMessageId;
        execution.status = status;
        execution.phase = phase;
        execution.executionAttemptNumber = 0L;
        execution.ownerUserId = ownerUserId;
        execution.requestId = requestId;
        execution.queuedAt = queuedAt;
        execution.recoveryAttemptCount = 0;
        execution.requestType = requestType;
        execution.requestIdempotencyKey = idempotencyKey;
        execution.requestPayloadHash = payloadHash;
        execution.executionBudgetMs = executionBudgetMs;
        execution.activeExecutionMs = 0L;
        execution.version = 0L;
        return execution;
    }
}
