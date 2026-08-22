package com.datagent.agentv2.persistence.service.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.ConversationSessionUserStateEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConversationSessionUserStateMapper
        extends BaseMapper<ConversationSessionUserStateEntity> {

    /** 为当前用户补齐同租户可见会话的已读状态。 */
    @InterceptorIgnore(tenantLine = "true")
    @Insert(
            """
            insert into conversation_session_user_state(
                tenant_id, user_id, conversation_session_id)
            select #{tenantId}, #{userId}, session.conversation_session_id
              from conversation_session session
             where session.tenant_id = #{tenantId} and session.is_deleted = false
            on conflict (tenant_id, user_id, conversation_session_id) do nothing
            """)
    int insertMissingVisible(@Param("tenantId") String tenantId, @Param("userId") String userId);
}
