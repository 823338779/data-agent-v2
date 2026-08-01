package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 持久化模型请求的一次工具执行及其幂等、恢复和结果关联信息。 */
@Getter
@Setter
@TableName("tool_execution")
public final class ToolExecutionEntity extends TenantAuditedEntity {

    /** 工具执行的业务唯一标识。 */
    private String toolExecutionId;

    /** 工具执行所属 Agent 执行的业务标识。 */
    private String executionId;

    /** 工具调用所属模型调用序号。 */
    private Long modelInvocationSequence;

    /** 当前工具执行所属的 Agent 执行尝试编号，用于恢复 fencing。 */
    private Long executionAttemptNumber;

    /** 产生工具调用的 assistant 会话消息标识。 */
    private String assistantConversationMessageId;

    /** provider 在当前模型调用内生成的工具调用标识。 */
    private String toolCallId;

    /** 注册表中的工具名称。 */
    private String toolName;

    /** 持久化执行状态：prepared、executing、succeeded 或 failed。 */
    private String status;

    /** 工具实际开始执行的累计次数。 */
    private Integer executionCount;

    /** 支持幂等的工具在首次准备时固定下来的幂等键。 */
    private String idempotencyKey;

    /** terminal 工具执行唯一关联的 tool observation 会话消息标识。 */
    private String observationConversationMessageId;

    /** 经过清理的稳定失败分类，不保存敏感异常正文。 */
    private String failureCode;

    /** 最近一次工具执行开始时间。 */
    private Instant startedAt;

    /** 工具执行进入 terminal 状态的时间。 */
    private Instant completedAt;

    /** 乐观锁版本，用于拒绝并发或旧 owner 更新。 */
    @Version private Long version;
}
