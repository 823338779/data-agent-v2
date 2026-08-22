package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 持久化一段连续对话的元数据，以及消息和执行事件的会话级序号水位。 */
@Getter
@Setter
@TableName("conversation_session")
public final class ConversationSessionEntity extends TenantAuditedEntity {

    /** 会话的业务唯一标识，对应数据库 conversation_session_id 列。 */
    private String conversationSessionId;

    /** 用户可见的会话标题。 */
    private String title;

    /** 用于检索、评测隔离或业务分类的会话标签。 */
    private String tag;

    /** 会话用途类型，用于区分交互式对话和其他来源。 */
    private String sessionType;

    /** 创建该会话的租户用户业务标识。 */
    private String createdByUserId;

    /** 当前已分配的最大会话消息序号。 */
    private Long conversationMessageSequence;

    /** 当前已分配的最大执行事件序号。 */
    private Long executionEventSequence;

    /** 最近一次影响会话列表状态或内容的时间，供会话摘要收敛扫描。 */
    private Instant lastActivityAt;

    /** 乐观锁版本，防止并发追加消息或事件时覆盖序号水位。 */
    @Version private Long version;
}
