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
@TableName("agent_message")
public final class AgentMessageEntity extends TenantAuditedEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String sessionId;
    private String runId;
    private String role;
    private String content;
    private Long sequence;
    private String authorUserId;

    @Version private Long version;
}
