package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
        return baseMapper.insert(outbox) == 1;
    }

    public List<NotificationOutboxMessageEntity> due(
            String notificationType, Instant now, int limit) {
        // 使用无 count 的分页 Wrapper 生成数据库 LIMIT，避免拼接页大小。
        IPage<NotificationOutboxMessageEntity> page = new Page<>(1, limit, false);
        return baseMapper
                .selectPage(
                        page,
                        Wrappers.lambdaQuery(NotificationOutboxMessageEntity.class)
                                .eq(
                                        NotificationOutboxMessageEntity::getNotificationType,
                                        notificationType)
                                .isNull(NotificationOutboxMessageEntity::getPublishedAt)
                                .le(NotificationOutboxMessageEntity::getNextAttemptAt, now)
                                .orderByAsc(NotificationOutboxMessageEntity::getId))
                .getRecords();
    }

    public boolean markPublished(
            String notificationMessageId, long expectedVersion, Instant publishedAt) {
        // 版本和未发布条件共同保证并发发布者只有一个能够提交终态。
        NotificationOutboxMessageEntity changes = new NotificationOutboxMessageEntity();
        changes.setPublishedAt(publishedAt);
        return baseMapper.update(
                        changes,
                        Wrappers.lambdaUpdate(NotificationOutboxMessageEntity.class)
                                .eq(
                                        NotificationOutboxMessageEntity::getNotificationMessageId,
                                        notificationMessageId)
                                .eq(NotificationOutboxMessageEntity::getVersion, expectedVersion)
                                .isNull(NotificationOutboxMessageEntity::getPublishedAt)
                                .set(NotificationOutboxMessageEntity::getLastError, null)
                                .setIncrBy(NotificationOutboxMessageEntity::getVersion, 1))
                == 1;
    }

    public boolean markFailed(
            String notificationMessageId,
            long expectedVersion,
            Instant nextAttemptAt,
            String lastError,
            Instant failedAt) {
        // 失败次数和版本由同一条 Wrapper UPDATE 原子递增。
        NotificationOutboxMessageEntity changes = new NotificationOutboxMessageEntity();
        changes.setNextAttemptAt(nextAttemptAt);
        changes.setLastError(lastError);
        return baseMapper.update(
                        changes,
                        Wrappers.lambdaUpdate(NotificationOutboxMessageEntity.class)
                                .eq(
                                        NotificationOutboxMessageEntity::getNotificationMessageId,
                                        notificationMessageId)
                                .eq(NotificationOutboxMessageEntity::getVersion, expectedVersion)
                                .isNull(NotificationOutboxMessageEntity::getPublishedAt)
                                .setIncrBy(NotificationOutboxMessageEntity::getAttemptCount, 1)
                                .setIncrBy(NotificationOutboxMessageEntity::getVersion, 1))
                == 1;
    }
}
