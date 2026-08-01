package com.datagent.agentv2.persistence.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.NotificationOutboxMessageEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NotificationOutboxMessageMapper
        extends BaseMapper<NotificationOutboxMessageEntity> {

    @Insert(
            """
            insert into notification_outbox_message(
                notification_message_id, tenant_id, notification_type, aggregate_type, aggregate_id,
                aggregate_version, payload, next_attempt_at)
            values(#{notificationMessageId}, #{tenantId}, #{notificationType}, #{aggregateType}, #{aggregateId},
                #{aggregateVersion}, cast(#{payload} as jsonb), #{nextAttemptAt})
            """)
    int insertControl(NotificationOutboxMessageEntity outbox);

    @Select(
            """
            select * from notification_outbox_message
             where notification_type = #{notificationType}
               and published_at is null and is_deleted = false
               and next_attempt_at <= #{now}
             order by id
             limit #{limit}
            """)
    List<NotificationOutboxMessageEntity> selectDue(
            @Param("notificationType") String notificationType,
            @Param("now") Instant now,
            @Param("limit") int limit);

    @Update(
            """
            update notification_outbox_message
               set published_at = #{publishedAt}, updated_at = #{publishedAt},
                   last_error = null, version = version + 1
             where notification_message_id = #{notificationMessageId} and version = #{expectedVersion}
               and published_at is null and is_deleted = false
            """)
    int markPublished(
            @Param("notificationMessageId") String notificationMessageId,
            @Param("expectedVersion") long expectedVersion,
            @Param("publishedAt") Instant publishedAt);

    @Update(
            """
            update notification_outbox_message
               set attempt_count = attempt_count + 1, next_attempt_at = #{nextAttemptAt},
                   last_error = #{lastError}, updated_at = #{failedAt}, version = version + 1
             where notification_message_id = #{notificationMessageId} and version = #{expectedVersion}
               and published_at is null and is_deleted = false
            """)
    int markFailed(
            @Param("notificationMessageId") String notificationMessageId,
            @Param("expectedVersion") long expectedVersion,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("lastError") String lastError,
            @Param("failedAt") Instant failedAt);
}
