package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 持久化 Agent 执行时间线中的一个可重放事件。 */
@Getter
@Setter
@TableName("agent_execution_event")
public final class AgentExecutionEventEntity extends TenantAuditedEntity {

    private String executionEventId;
    private String conversationSessionId;
    private String executionId;
    private Long executionEventSequence;
    private String type;
    private Instant occurredAt;

    @Version private Long version;
}
