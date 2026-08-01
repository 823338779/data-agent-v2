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
