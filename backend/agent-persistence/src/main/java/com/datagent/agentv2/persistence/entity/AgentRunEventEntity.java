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

    /** Run 事件的业务唯一标识，对应数据库 event_id 列。 */
    private String eventId;

    private String sessionId;
    private String runId;
    private Long sequence;
    private String type;
    private String payload;
    private Instant occurredAt;

    @Version private Long version;
}
