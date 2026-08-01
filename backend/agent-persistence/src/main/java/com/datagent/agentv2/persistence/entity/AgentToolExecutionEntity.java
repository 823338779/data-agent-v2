package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("agent_tool_execution")
public final class AgentToolExecutionEntity extends TenantAuditedEntity {

    /** 工具执行的业务唯一标识。 */
    private String toolExecutionId;

    /** 工具执行所属 Run 的业务标识。 */
    private String runId;

    /** 工具调用所属 Model Turn 序号。 */
    private Long modelTurnSequence;

    /** 当前执行所属 Run attempt，用于恢复 fencing。 */
    private Long executionAttempt;

    /** 产生工具调用的 assistant 消息标识。 */
    private String assistantMessageId;

    /** provider 在当前 Model Turn 内生成的工具调用标识。 */
    private String toolCallId;

    /** 注册表中的工具名称。 */
    private String toolName;

    /** 持久化执行状态：prepared、executing、succeeded 或 failed。 */
    private String status;

    /** 工具实际开始执行的累计次数。 */
    private Integer executionCount;

    /** 支持幂等的工具在首次准备时固定下来的幂等键。 */
    private String idempotencyKey;

    /** terminal execution 唯一关联的 tool Observation 消息标识。 */
    private String observationMessageId;

    /** 经过清理的稳定失败分类，不保存敏感异常正文。 */
    private String failureCode;

    /** 最近一次工具执行开始时间。 */
    private Instant startedAt;

    /** 工具执行进入 terminal 状态的时间。 */
    private Instant completedAt;

    /** 乐观锁版本，用于拒绝并发或旧 owner 更新。 */
    @Version private Long version;
}
