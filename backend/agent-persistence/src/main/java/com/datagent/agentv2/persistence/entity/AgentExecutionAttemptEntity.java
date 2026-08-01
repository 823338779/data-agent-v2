package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 记录一次 Agent 执行被工作实例领取或接管后形成的独立执行尝试。 */
@Getter
@Setter
@TableName("agent_execution_attempt")
public final class AgentExecutionAttemptEntity extends TenantAuditedEntity {

    /** 执行尝试的业务唯一标识，对应 execution_attempt_id 列。 */
    private String executionAttemptId;

    /** 尝试所属 Agent 执行的业务标识。 */
    private String executionId;

    /** 同一 Agent 执行内从 1 开始递增的尝试编号。 */
    private Long executionAttemptNumber;

    /** 持有本次执行尝试 lease 的工作实例标识。 */
    private String ownerInstanceId;

    /** 执行尝试状态，用于区分活跃、完成和失败尝试。 */
    private String status;

    /** 创建该尝试的原因，例如首次领取或 lease 恢复接管。 */
    private String resumeReason;

    /** 本次尝试开始持有执行权的时间。 */
    private Instant startedAt;

    /** 本次尝试释放执行权或终止的时间。 */
    private Instant endedAt;

    /** 本次尝试失败时的稳定失败分类。 */
    private String failureCode;

    /** 乐观锁版本，防止并发结束同一次执行尝试。 */
    @Version private Long version;
}
