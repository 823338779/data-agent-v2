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
@TableName("agent_run_event")
public final class AgentRunEventEntity extends TenantAuditedEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String sessionId;
    private String runId;
    private Long sequence;
    private String type;
    private String payload;
    private Instant occurredAt;

    @Version private Long version;
}
