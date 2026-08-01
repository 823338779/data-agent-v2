package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 持久化当前 Agent 执行输出的最新完整检查点，用于断线和跨实例恢复。 */
@Getter
@Setter
@TableName("agent_execution_stream_checkpoint")
public final class AgentExecutionStreamCheckpointEntity extends TenantAuditedEntity {

    /** 执行流检查点的业务唯一标识，对应 execution_stream_checkpoint_id 列。 */
    private String executionStreamCheckpointId;

    /** 当前检查点所属 Agent 执行的业务标识。 */
    private String executionId;

    /** 当前 owner 的执行尝试编号；queued 检查点为 0。 */
    private Long executionAttemptNumber;

    /** 当前正在输出的模型调用序号；queued 检查点为 0。 */
    private Long modelInvocationSequence;

    /** 当前流阶段，用于 snapshot 恢复展示状态。 */
    private String phase;

    /** 当前 attempt/turn 内最后已发布的 delta 序号。 */
    private Long outputDeltaSequence;

    /** 当前模型调用已经累计并写入检查点的 reasoning。 */
    private String reasoning;

    /** 当前模型调用已经累计并写入检查点的回答草稿。 */
    private String answer;

    /** 最近一次完整流投影成功写入数据库的时间。 */
    private Instant checkpointedAt;

    /** reasoning 可被清理的时间边界。 */
    private Instant reasoningExpiresAt;

    /** 乐观锁版本，用于拒绝旧 owner 或并发检查点覆盖。 */
    @Version private Long version;
}
