package com.datagent.agentv2.persistence.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.ToolExecutionEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ToolExecutionMapper extends BaseMapper<ToolExecutionEntity> {

    @Update(
            """
            update tool_execution e
               set status = 'executing',
                   execution_attempt_number = #{executionAttemptNumber},
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
                    select 1 from agent_execution r
                     where r.tenant_id = e.tenant_id
                       and r.execution_id = e.execution_id
                       and r.status = 'running'
                       and r.owner_instance_id = #{ownerInstanceId}
                       and r.execution_attempt_number = #{executionAttemptNumber}
               )
            """)
    int startExecution(
            @Param("tenantId") String tenantId,
            @Param("toolExecutionId") String toolExecutionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("startedAt") Instant startedAt,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("executionAttemptNumber") long executionAttemptNumber);

    @Update(
            """
            update tool_execution e
               set status = #{status},
                   observation_conversation_message_id = #{observationConversationMessageId},
                   failure_code = #{failureCode},
                   completed_at = #{completedAt},
                   updated_at = #{completedAt},
                   version = version + 1
             where e.tenant_id = #{tenantId}
               and e.tool_execution_id = #{toolExecutionId}
               and e.version = #{expectedVersion}
               and e.status = 'executing'
               and exists (
                    select 1 from agent_execution r
                     where r.tenant_id = e.tenant_id
                       and r.execution_id = e.execution_id
                       and r.status = 'running'
                       and r.owner_instance_id = #{ownerInstanceId}
                       and r.execution_attempt_number = #{executionAttemptNumber}
               )
            """)
    int completeExecution(
            @Param("tenantId") String tenantId,
            @Param("toolExecutionId") String toolExecutionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("status") String status,
            @Param("observationConversationMessageId") String observationConversationMessageId,
            @Param("completedAt") Instant completedAt,
            @Param("failureCode") String failureCode,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("executionAttemptNumber") long executionAttemptNumber);
}
