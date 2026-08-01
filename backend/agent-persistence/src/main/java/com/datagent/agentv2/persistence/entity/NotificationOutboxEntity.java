package com.datagent.agentv2.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("notification_outbox")
public final class NotificationOutboxEntity extends TenantAuditedEntity {

    /** 通知消息的业务唯一标识，对应数据库 notification_id 列。 */
    private String notificationId;

    private String notificationType;
    private String aggregateType;
    private String aggregateId;
    private Long aggregateVersion;
    private String payload;
    private Instant publishedAt;
    private Integer attemptCount;
    private Instant nextAttemptAt;
    private String lastError;

    @Version private Long version;
}
