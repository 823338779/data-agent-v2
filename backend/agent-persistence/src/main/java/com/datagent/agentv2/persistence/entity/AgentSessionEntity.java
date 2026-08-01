package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("agent_session")
public final class AgentSessionEntity extends TenantAuditedEntity {

    /** 会话的业务唯一标识，对应数据库 session_id 列。 */
    private String sessionId;

    private String title;
    private String tag;
    private String sessionType;
    private String createdBy;
    private Long messageSequence;
    private Long eventSequence;

    @Version private Long version;
}
