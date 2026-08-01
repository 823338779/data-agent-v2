package com.datagent.agentv2.persistence.conversation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.datagent.agentv2.persistence.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("agent_session")
public final class AgentSessionEntity extends TenantAuditedEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String title;
    private String tag;
    private String sessionType;
    private String createdBy;
    private Long messageSequence;
    private Long eventSequence;

    @Version private Long version;
}
