package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 持久化 ReAct 循环中的一次模型调用及其可恢复边界。 */
@Getter
@Setter
@TableName("model_invocation")
public final class ModelInvocationEntity extends TenantAuditedEntity {

    /** 模型调用的业务唯一标识，对应 model_invocation_id 列。 */
    private String modelInvocationId;

    /** 模型调用所属 Agent 执行的业务标识。 */
    private String executionId;

    /** Agent 执行内单调递增的模型调用序号。 */
    private Long modelInvocationSequence;

    /** 创建本次模型调用的执行尝试编号，用于隔离旧 owner 写入。 */
    private Long executionAttemptNumber;

    /** 本次模型输入允许读取的最大会话消息序号。 */
    private Long inputUpToConversationMessageSequence;

    /** 模型调用恢复状态：streaming、completed、abandoned 或 failed。 */
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
