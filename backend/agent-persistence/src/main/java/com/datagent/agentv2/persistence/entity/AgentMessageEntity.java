package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("agent_message")
public final class AgentMessageEntity extends TenantAuditedEntity {

    /** 消息的业务唯一标识，对应数据库 message_id 列。 */
    private String messageId;

    private String sessionId;
    private String runId;
    private String role;
    private String content;
    private Long sequence;
    private String authorUserId;

    @Version private Long version;
}
