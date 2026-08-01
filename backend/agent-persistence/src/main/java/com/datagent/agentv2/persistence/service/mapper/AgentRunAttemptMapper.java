package com.datagent.agentv2.persistence.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.AgentRunAttemptEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentRunAttemptMapper extends BaseMapper<AgentRunAttemptEntity> {

    @Update(
            """
            update agent_run_attempt
               set status = #{status}, ended_at = #{endedAt}, failure_code = #{failureCode},
                   updated_at = #{endedAt}, version = version + 1
             where run_id = #{runId} and execution_attempt = #{executionAttempt}
               and status in ('running', 'waiting') and is_deleted = false
            """)
    int finish(
            @Param("runId") String runId,
            @Param("executionAttempt") long executionAttempt,
            @Param("status") String status,
            @Param("endedAt") Instant endedAt,
            @Param("failureCode") String failureCode);
}
