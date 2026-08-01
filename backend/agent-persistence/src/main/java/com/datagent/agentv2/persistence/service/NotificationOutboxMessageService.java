package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.NotificationOutboxMessageEntity;
import com.datagent.agentv2.persistence.service.mapper.NotificationOutboxMessageMapper;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class NotificationOutboxMessageService
        extends ServiceImpl<NotificationOutboxMessageMapper, NotificationOutboxMessageEntity> {

    public boolean insertControl(NotificationOutboxMessageEntity outbox) {
        if (outbox.getTenantId() == null) {
            outbox.setTenantId(TenantContext.requiredTenantId());
        }
        return baseMapper.insertControl(outbox) == 1;
    }

    public List<NotificationOutboxMessageEntity> due(
            String notificationType, Instant now, int limit) {
        return baseMapper.selectDue(notificationType, now, limit);
    }

    public boolean markPublished(
            String notificationMessageId, long expectedVersion, Instant publishedAt) {
        return baseMapper.markPublished(notificationMessageId, expectedVersion, publishedAt) == 1;
    }

    public boolean markFailed(
            String notificationMessageId,
            long expectedVersion,
            Instant nextAttemptAt,
            String lastError,
            Instant failedAt) {
        return baseMapper.markFailed(
                        notificationMessageId, expectedVersion, nextAttemptAt, lastError, failedAt)
                == 1;
    }
}
