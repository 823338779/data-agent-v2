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
@TableName("agent_run")
public final class AgentRunEntity extends TenantAuditedEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String sessionId;
    private String triggeringMessageId;
    private String status;
    private String phase;
    private Long executionAttempt;
    private String ownerInstanceId;
    private String requestId;
    private Instant startedAt;
    private Instant completedAt;
    private Instant failedAt;
    private String failureCode;
    private String failureDetail;
    private Instant hardDeadlineAt;

    @Version private Long version;
}
