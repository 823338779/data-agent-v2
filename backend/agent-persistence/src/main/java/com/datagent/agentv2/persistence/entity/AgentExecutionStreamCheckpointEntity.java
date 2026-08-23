package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

/** 持久化当前 Agent 执行输出的最新完整检查点。 */
@Getter
@Setter
@TableName("agent_execution_stream_checkpoint")
public final class AgentExecutionStreamCheckpointEntity extends TenantAuditedEntity {

    private String executionId;
    private Long modelInvocationSequence;
    private String phase;
    private Long outputDeltaSequence;
    private String answer;

    @Version private Long version;
}
