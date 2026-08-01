package com.datagent.agentv2.persistence.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.AgentSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentSessionMapper extends BaseMapper<AgentSessionEntity> {

    @Select("select * from agent_session where uuid = #{sessionId} for update")
    AgentSessionEntity selectByIdForUpdate(@Param("sessionId") String sessionId);

    @Update(
            """
            update agent_session
               set event_sequence = event_sequence + 1,
                   version = version + 1,
                   updated_at = current_timestamp
             where uuid = #{sessionId}
               and is_deleted = false
            """)
    int incrementEventSequence(@Param("sessionId") String sessionId);
}
