package com.datagent.agentv2.persistence.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.AgentToolExecutionEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentToolExecutionMapper extends BaseMapper<AgentToolExecutionEntity> {

    @Update(
            """
            update agent_tool_execution e
               set status = 'executing',
                   execution_attempt = #{executionAttempt},
                   execution_count = execution_count + 1,
                   idempotency_key = coalesce(idempotency_key, #{idempotencyKey}),
                   started_at = #{startedAt},
                   updated_at = #{startedAt},
                   version = version + 1
             where e.tenant_id = #{tenantId}
               and e.tool_execution_id = #{toolExecutionId}
               and e.version = #{expectedVersion}
               and e.status in ('prepared', 'executing')
               and exists (
                    select 1 from agent_run r
                     where r.tenant_id = e.tenant_id
                       and r.run_id = e.run_id
                       and r.status = 'running'
                       and r.owner_instance_id = #{ownerInstanceId}
                       and r.execution_attempt = #{executionAttempt}
               )
            """)
    int startExecution(
            @Param("tenantId") String tenantId,
            @Param("toolExecutionId") String toolExecutionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("startedAt") Instant startedAt,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("executionAttempt") long executionAttempt);

    @Update(
            """
            update agent_tool_execution e
               set status = #{status},
                   observation_message_id = #{observationMessageId},
                   failure_code = #{failureCode},
                   completed_at = #{completedAt},
                   updated_at = #{completedAt},
                   version = version + 1
             where e.tenant_id = #{tenantId}
               and e.tool_execution_id = #{toolExecutionId}
               and e.version = #{expectedVersion}
               and e.status = 'executing'
               and exists (
                    select 1 from agent_run r
                     where r.tenant_id = e.tenant_id
                       and r.run_id = e.run_id
                       and r.status = 'running'
                       and r.owner_instance_id = #{ownerInstanceId}
                       and r.execution_attempt = #{executionAttempt}
               )
            """)
    int completeExecution(
            @Param("tenantId") String tenantId,
            @Param("toolExecutionId") String toolExecutionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("status") String status,
            @Param("observationMessageId") String observationMessageId,
            @Param("completedAt") Instant completedAt,
            @Param("failureCode") String failureCode,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("executionAttempt") long executionAttempt);
}
