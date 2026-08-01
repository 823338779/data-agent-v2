package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 持久化等待发布或重试的跨实例通知消息。 */
@Getter
@Setter
@TableName("notification_outbox_message")
public final class NotificationOutboxMessageEntity extends TenantAuditedEntity {

    /** 通知消息的业务唯一标识，对应 notification_message_id 列。 */
    private String notificationMessageId;

    /** 决定发布通道和反序列化方式的通知类型。 */
    private String notificationType;

    /** 产生通知的聚合类型。 */
    private String aggregateType;

    /** 产生通知的聚合业务标识。 */
    private String aggregateId;

    /** 生成通知时的聚合版本，用于通知去重和乱序判断。 */
    private Long aggregateVersion;

    /** 待发布的 JSON 通知负载。 */
    private String payload;

    /** 通知首次成功发布的时间；未发布时为空。 */
    private Instant publishedAt;

    /** 已发生的发布尝试次数。 */
    private Integer attemptCount;

    /** 失败后允许再次尝试发布的最早时间。 */
    private Instant nextAttemptAt;

    /** 最近一次发布失败的诊断信息。 */
    private String lastError;

    /** 乐观锁版本，防止多个发布者重复更新同一消息。 */
    @Version private Long version;
}
