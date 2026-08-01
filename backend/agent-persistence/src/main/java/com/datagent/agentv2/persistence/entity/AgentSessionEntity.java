package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("agent_session")
public final class AgentSessionEntity extends TenantAuditedEntity {

    private String title;
    private String tag;
    private String sessionType;
    private String createdBy;
    private Long messageSequence;
    private Long eventSequence;

    @Version private Long version;
}
