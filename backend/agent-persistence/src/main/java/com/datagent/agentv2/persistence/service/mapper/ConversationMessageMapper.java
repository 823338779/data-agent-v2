package com.datagent.agentv2.persistence.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.ConversationMessageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ConversationMessageMapper extends BaseMapper<ConversationMessageEntity> {

    /** 按执行标识锁定唯一 assistant 中心消息，序列化后续正文和 ProcessItem 更新。 */
    @Select(
            """
            select * from conversation_message
             where execution_id = #{executionId}
               and role = 'assistant' and is_deleted = false
             for update
            """)
    ConversationMessageEntity selectAssistantByExecutionIdForUpdate(
            @Param("executionId") String executionId);
}
