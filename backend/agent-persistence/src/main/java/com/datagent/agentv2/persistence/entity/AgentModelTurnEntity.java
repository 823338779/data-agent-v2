package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("agent_model_turn")
public final class AgentModelTurnEntity extends TenantAuditedEntity {

    /** Model Turn 的业务唯一标识。 */
    private String modelTurnId;

    /** Model Turn 所属 Run 的业务标识。 */
    private String runId;

    /** Run 内单调递增的 Model Turn 序号。 */
    private Long modelTurnSequence;

    /** 创建本轮的 Run execution attempt，用于隔离旧 owner 写入。 */
    private Long executionAttempt;

    /** 本轮模型输入允许读取的最大消息序号。 */
    private Long inputUpToMessageSequence;

    /** 本轮恢复状态：streaming、completed、abandoned 或 failed。 */
    private String status;

    /** provider reasoning 检查点；这是 Model Turn 唯一允许的大文本。 */
    private String reasoning;

    /** reasoning 检查点的到期清理时间。 */
    private Instant reasoningExpiresAt;

    /** 模型调用开始时间。 */
    private Instant startedAt;

    /** 模型调用形成完整结果或被归档的时间。 */
    private Instant completedAt;

    /** 乐观锁版本，用于拒绝并发或旧 owner 更新。 */
    @Version private Long version;
}
