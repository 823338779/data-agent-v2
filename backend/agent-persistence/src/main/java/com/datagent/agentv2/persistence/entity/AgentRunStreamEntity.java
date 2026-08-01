package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("agent_run_stream")
public final class AgentRunStreamEntity extends TenantAuditedEntity {

    /** Run 流投影的业务唯一标识，对应数据库 stream_id 列。 */
    private String streamId;

    /** 当前投影所属 Run 的业务标识。 */
    private String runId;

    /** 当前 owner 的执行尝试；queued 投影为 0。 */
    private Long executionAttempt;

    /** 当前正在输出的 Model Turn 序号；queued 投影为 0。 */
    private Long modelTurnSequence;

    /** 当前流阶段，用于 snapshot 恢复展示状态。 */
    private String phase;

    /** 当前 attempt/turn 内最后已发布的 delta 序号。 */
    private Long streamSequence;

    /** 当前 Model Turn 已累计并 checkpoint 的 reasoning。 */
    private String reasoning;

    /** 当前 Model Turn 已累计并 checkpoint 的回答草稿。 */
    private String answer;

    /** 最近一次完整流投影成功写入数据库的时间。 */
    private Instant checkpointedAt;

    /** reasoning 可被清理的时间边界。 */
    private Instant reasoningExpiresAt;

    @Version private Long version;
}
