package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("agent_message")
public final class AgentMessageEntity extends TenantAuditedEntity {

    private String sessionId;
    private String runId;
    private String role;
    private String content;
    private Long sequence;
    private String authorUserId;

    @Version private Long version;
}
