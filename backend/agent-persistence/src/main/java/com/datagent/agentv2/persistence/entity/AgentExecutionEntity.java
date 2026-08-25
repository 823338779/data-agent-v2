package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 持久化一条本机 Agent 执行。 */
@Getter
@Setter
@TableName("agent_execution")
public final class AgentExecutionEntity extends TenantAuditedEntity {

    /** 执行业务唯一标识，也是本机任务注册和取消键。 */
    private String executionId;

    /** 执行所属会话业务标识。 */
    private String conversationSessionId;

    /** 触发执行的用户消息业务标识。 */
    private String triggeringConversationMessageId;

    /** 持久状态：running、done、cancelled 或 failed。 */
    private String status;

    /** 调用方提供的会话级幂等键。 */
    private String requestIdempotencyKey;

    /** 规范化请求负载的 SHA-256。 */
    private String requestPayloadHash;

    /** 失败分类码；未失败时为空。 */
    private String failureCode;

    /** 已脱敏的失败诊断摘要。 */
    private String failureDetail;

    /** 执行硬截止时刻，超过后禁止继续副作用。 */
    private Instant hardDeadlineAt;

    /** 状态条件更新使用的乐观锁版本。 */
    @Version private Long version;
}
