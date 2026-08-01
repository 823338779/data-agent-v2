package com.datagent.agentv2.persistence.service.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.datagent.agentv2.persistence.entity.ConversationSessionUserStateEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ConversationSessionUserStateMapper {

    /** 按用户稳定顺序读取可见会话状态，供列表和 activity 快照复用。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select(
            """
            select * from conversation_session_user_state
             where tenant_id = #{tenantId} and user_id = #{userId}
             order by sort_order, conversation_session_id
            """)
    List<ConversationSessionUserStateEntity> selectOrderedByUserId(
            @Param("tenantId") String tenantId, @Param("userId") String userId);

    /** 为当前用户补齐同租户可见会话，并稳定追加到现有顺序末尾。 */
    @InterceptorIgnore(tenantLine = "true")
    @Insert(
            """
            with missing as (
                select session.conversation_session_id,
                       row_number() over (order by session.last_activity_at desc,
                                                   session.conversation_session_id desc) - 1 as offset_value
                  from conversation_session session
                 where session.tenant_id = #{tenantId} and session.is_deleted = false
                   and not exists (
                       select 1 from conversation_session_user_state state
                        where state.tenant_id = #{tenantId} and state.user_id = #{userId}
                          and state.conversation_session_id = session.conversation_session_id)
            ), tail as (
                select coalesce(max(sort_order) + 1, 0) as first_order
                  from conversation_session_user_state
                 where tenant_id = #{tenantId} and user_id = #{userId}
            )
            insert into conversation_session_user_state(
                tenant_id, user_id, conversation_session_id, sort_order)
            select #{tenantId}, #{userId}, missing.conversation_session_id,
                   tail.first_order + missing.offset_value
              from missing cross join tail
            on conflict (tenant_id, user_id, conversation_session_id) do nothing
            """)
    int insertMissingVisible(@Param("tenantId") String tenantId, @Param("userId") String userId);

    /** 新会话插入前整体后移当前用户顺序，为置顶位置腾出空间。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update(
            """
            update conversation_session_user_state
               set sort_order = sort_order + 1, updated_at = current_timestamp,
                   version = version + 1
             where tenant_id = #{tenantId} and user_id = #{userId}
            """)
    int shiftForNewSession(@Param("tenantId") String tenantId, @Param("userId") String userId);

    /** 为创建者建立新会话的初始未读和置顶状态。 */
    @InterceptorIgnore(tenantLine = "true")
    @Insert(
            """
            insert into conversation_session_user_state(
                tenant_id, user_id, conversation_session_id, sort_order)
            values (#{tenantId}, #{userId}, #{conversationSessionId}, 0)
            """)
    int insertCreatedSession(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("conversationSessionId") String conversationSessionId);

    /** 已读游标仅单调推进到请求序号和当前可通知水位的较小值。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update(
            """
            update conversation_session_user_state state
               set last_read_event_sequence = greatest(
                       state.last_read_event_sequence,
                       least(#{upToSequence}, session.last_notifiable_event_sequence)),
                   updated_at = current_timestamp, version = state.version + 1
              from conversation_session session
             where state.tenant_id = #{tenantId} and state.user_id = #{userId}
               and state.conversation_session_id = #{conversationSessionId}
               and session.tenant_id = state.tenant_id
               and session.conversation_session_id = state.conversation_session_id
               and state.last_read_event_sequence < least(
                       #{upToSequence}, session.last_notifiable_event_sequence)
            """)
    int advanceReadCursor(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("conversationSessionId") String conversationSessionId,
            @Param("upToSequence") long upToSequence);

    /** 把现有顺序移入无冲突临时区间，随后写回连续整数。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update(
            """
            update conversation_session_user_state
               set sort_order = sort_order + #{offset}, updated_at = current_timestamp,
                   version = version + 1
             where tenant_id = #{tenantId} and user_id = #{userId}
            """)
    int moveToTemporaryRange(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("offset") long offset);

    /** 将一个会话写入客户端提交的连续目标位置。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update(
            """
            update conversation_session_user_state
               set sort_order = #{sortOrder}, updated_at = current_timestamp,
                   version = version + 1
             where tenant_id = #{tenantId} and user_id = #{userId}
               and conversation_session_id = #{conversationSessionId}
            """)
    int updateSortOrder(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("conversationSessionId") String conversationSessionId,
            @Param("sortOrder") long sortOrder);
}
