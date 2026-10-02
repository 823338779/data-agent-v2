package com.datagent.agentv2.conversation.domain;

import com.datagent.agentv2.conversation.enums.ExecutionFailureCode;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 一次用户请求触发的本机 Agent 执行。 */
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AgentExecution {

    /** 执行业务唯一标识，也是当前进程内任务注册键。 */
    private String executionId;

    /** 执行所属会话业务标识。 */
    private String conversationSessionId;

    /** 触发本次执行的用户消息标识。 */
    private String triggeringConversationMessageId;

    /** 当前持久状态；非法字符串不能再进入领域状态机。 */
    private ExecutionStatus status;

    /** 调用方幂等键，在同一租户会话内唯一。 */
    private String requestIdempotencyKey;

    /** 规范化请求负载的 SHA-256，用于发现同键不同载荷。 */
    private String requestPayloadHash;

    /** 失败分类码；未失败时为空。 */
    private ExecutionFailureCode failureCode;

    /** 可安全记录的失败摘要，不得包含凭据或完整敏感输入。 */
    private String failureDetail;

    /** 执行硬截止时刻，超过后不得继续产生模型、工具或压缩副作用。 */
    private Instant hardDeadlineAt;

    /** 执行状态迁移使用的乐观锁版本。 */
    private Long version;

    /** 从完整存储快照恢复领域状态；Builder 只负责组装，构造时统一校验不变量。 */
    @Builder
    private AgentExecution(
            String executionId,
            String conversationSessionId,
            String triggeringConversationMessageId,
            ExecutionStatus status,
            String requestIdempotencyKey,
            String requestPayloadHash,
            ExecutionFailureCode failureCode,
            String failureDetail,
            Instant hardDeadlineAt,
            Long version) {
        this.executionId = DomainInvariants.text(executionId, "executionId");
        this.conversationSessionId =
                DomainInvariants.text(conversationSessionId, "conversationSessionId");
        this.triggeringConversationMessageId =
                DomainInvariants.text(
                        triggeringConversationMessageId, "triggeringConversationMessageId");
        this.status = java.util.Objects.requireNonNull(status, "status");
        this.requestIdempotencyKey =
                DomainInvariants.text(requestIdempotencyKey, "requestIdempotencyKey");
        this.requestPayloadHash = DomainInvariants.text(requestPayloadHash, "requestPayloadHash");
        this.failureCode = failureCode;
        this.failureDetail = failureDetail;
        this.hardDeadlineAt = java.util.Objects.requireNonNull(hardDeadlineAt, "hardDeadlineAt");
        this.version = DomainInvariants.nonnegative(version, "version");
    }

    /** 创建事务提交后将立即在本机启动的执行。 */
    public static AgentExecution running(
            String executionId,
            String conversationSessionId,
            String triggeringMessageId,
            String idempotencyKey,
            String payloadHash,
            Instant hardDeadlineAt) {
        AgentExecution execution = new AgentExecution();
        execution.executionId = requireText(executionId, "executionId");
        execution.conversationSessionId =
                requireText(conversationSessionId, "conversationSessionId");
        execution.triggeringConversationMessageId =
                requireText(triggeringMessageId, "triggeringMessageId");
        execution.status = ExecutionStatus.RUNNING;
        execution.requestIdempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        execution.requestPayloadHash = requireText(payloadHash, "payloadHash");
        execution.hardDeadlineAt = Objects.requireNonNull(hardDeadlineAt, "hardDeadlineAt");
        execution.version = 0L;
        return execution;
    }

    /** 仅允许从运行态取消；终态快照不得重新迁移。 */
    public void cancel() {
        if (status != ExecutionStatus.RUNNING) {
            throw new IllegalStateException("Only a running execution can be cancelled");
        }
        status = ExecutionStatus.CANCELLED;
    }

    /** 保存成功后接收数据库 CAS 版本，拒绝回退。 */
    public void acknowledgePersistence(long persistedVersion) {
        version = DomainInvariants.advancedVersion(version, persistedVersion);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
