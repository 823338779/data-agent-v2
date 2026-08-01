package com.datagent.agentv2.persistence.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.ConversationSessionEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ConversationSessionMapper extends BaseMapper<ConversationSessionEntity> {

    @Select(
            "select * from conversation_session where conversation_session_id = #{conversationSessionId} for update")
    ConversationSessionEntity selectByIdForUpdate(
            @Param("conversationSessionId") String conversationSessionId);

    @Update(
            """
            update conversation_session
               set execution_event_sequence = execution_event_sequence + 1,
                   version = version + 1,
                   updated_at = current_timestamp
             where conversation_session_id = #{conversationSessionId}
               and is_deleted = false
            """)
    int incrementExecutionEventSequence(
            @Param("conversationSessionId") String conversationSessionId);

    @Update(
            """
            update conversation_session
               set conversation_message_sequence = conversation_message_sequence + 1,
                   version = version + 1,
                   updated_at = current_timestamp
             where conversation_session_id = #{conversationSessionId}
               and is_deleted = false
            """)
    int incrementConversationMessageSequence(
            @Param("conversationSessionId") String conversationSessionId);

    /** 记录会话活动；仅可通知事件携带非空水位并推进未读边界。 */
    @Update(
            """
            update conversation_session
               set last_activity_at = greatest(last_activity_at, #{occurredAt}),
                   last_notifiable_event_sequence = case
                       when #{notifiableSequence,jdbcType=BIGINT} is null
                           then last_notifiable_event_sequence
                       else greatest(
                           last_notifiable_event_sequence,
                           #{notifiableSequence,jdbcType=BIGINT}) end,
                   updated_at = current_timestamp, version = version + 1
             where conversation_session_id = #{conversationSessionId}
               and is_deleted = false
            """)
    int recordActivity(
            @Param("conversationSessionId") String conversationSessionId,
            @Param("notifiableSequence") Long notifiableSequence,
            @Param("occurredAt") Instant occurredAt);
}
