package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.NotificationOutboxEntity;
import com.datagent.agentv2.persistence.service.mapper.NotificationOutboxMapper;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class NotificationOutboxService
        extends ServiceImpl<NotificationOutboxMapper, NotificationOutboxEntity> {

    public boolean insertControl(NotificationOutboxEntity outbox) {
        if (outbox.getTenantId() == null) {
            outbox.setTenantId(TenantContext.requiredTenantId());
        }
        return baseMapper.insertControl(outbox) == 1;
    }

    public List<NotificationOutboxEntity> due(String notificationType, Instant now, int limit) {
        return baseMapper.selectDue(notificationType, now, limit);
    }

    public boolean markPublished(String notificationId, long expectedVersion, Instant publishedAt) {
        return baseMapper.markPublished(notificationId, expectedVersion, publishedAt) == 1;
    }

    public boolean markFailed(
            String notificationId,
            long expectedVersion,
            Instant nextAttemptAt,
            String lastError,
            Instant failedAt) {
        return baseMapper.markFailed(
                        notificationId, expectedVersion, nextAttemptAt, lastError, failedAt)
                == 1;
    }
}
