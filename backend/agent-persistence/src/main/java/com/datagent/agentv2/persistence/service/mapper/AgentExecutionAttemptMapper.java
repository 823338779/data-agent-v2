package com.datagent.agentv2.persistence.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.AgentExecutionAttemptEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentExecutionAttemptMapper extends BaseMapper<AgentExecutionAttemptEntity> {

    @Update(
            """
            update agent_execution_attempt
               set status = #{status}, ended_at = #{endedAt}, failure_code = #{failureCode},
                   updated_at = #{endedAt}, version = version + 1
             where execution_id = #{executionId} and execution_attempt_number = #{executionAttemptNumber}
               and status in ('running', 'waiting') and is_deleted = false
            """)
    int finish(
            @Param("executionId") String executionId,
            @Param("executionAttemptNumber") long executionAttemptNumber,
            @Param("status") String status,
            @Param("endedAt") Instant endedAt,
            @Param("failureCode") String failureCode);
}
