package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("agent_run_attempt")
public final class AgentRunAttemptEntity extends TenantAuditedEntity {

    private String runId;
    private Long executionAttempt;
    private String ownerInstanceId;
    private String status;
    private String resumeReason;
    private Instant startedAt;
    private Instant endedAt;
    private String failureCode;

    @Version private Long version;
}
