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

    /** 执行事件的业务唯一标识，对应 execution_event_id 列。 */
    private String executionEventId;

    /** 事件所属对话会话的业务标识。 */
    private String conversationSessionId;

    /** 事件所属 Agent 执行的业务标识。 */
    private String executionId;

    /** 执行事件在所属会话内的单调递增业务序号。 */
    private Long executionEventSequence;

    /** 稳定事件类型，用于客户端投影和重放。 */
    private String type;

    /** 事件类型对应的 JSON 负载。 */
    private String payload;

    /** 业务事件实际发生的时间。 */
    private Instant occurredAt;

    /** 乐观锁版本，防止事件元数据被并发覆盖。 */
    @Version private Long version;
}
