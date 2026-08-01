package com.datagent.agentv2.persistence.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.NotificationOutboxEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NotificationOutboxMapper extends BaseMapper<NotificationOutboxEntity> {

    @Insert(
            """
            insert into notification_outbox(
                notification_id, tenant_id, notification_type, aggregate_type, aggregate_id,
                aggregate_version, payload, next_attempt_at)
            values(#{notificationId}, #{tenantId}, #{notificationType}, #{aggregateType}, #{aggregateId},
                #{aggregateVersion}, cast(#{payload} as jsonb), #{nextAttemptAt})
            """)
    int insertControl(NotificationOutboxEntity outbox);
}
