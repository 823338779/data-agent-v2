package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("agent_run_event")
public final class AgentRunEventEntity extends TenantAuditedEntity {

    private String sessionId;
    private String runId;
    private Long sequence;
    private String type;
    private String payload;
    private Instant occurredAt;

    @Version private Long version;
}
