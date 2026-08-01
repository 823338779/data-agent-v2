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

    private String runId;

    private Long executionAttempt;
    private Long modelTurnSequence;
    private String phase;
    private Long streamSequence;
    private String reasoning;
    private String answer;
    private Instant checkpointedAt;
    private Instant reasoningExpiresAt;

    @Version private Long version;
}
