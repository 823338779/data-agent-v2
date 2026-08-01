package com.datagent.agentv2.persistence.conversation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.datagent.agentv2.persistence.TenantAuditedEntity;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("agent_run_stream")
public final class AgentRunStreamEntity extends TenantAuditedEntity {

    @TableId(value = "run_id", type = IdType.INPUT)
    private String runId;

    private Long executionAttempt;
    private String phase;
    private Long streamSequence;
    private String thinking;
    private String answer;
    private Instant checkpointedAt;

    @Version private Long version;
}
