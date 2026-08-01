package com.datagent.agentv2.persistence.service.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.ModelInvocationEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ModelInvocationMapper extends BaseMapper<ModelInvocationEntity> {

    @Select(
            """
            select coalesce(max(model_invocation_sequence), 0) from model_invocation
             where execution_id = #{executionId} and is_deleted = false
            """)
    long maxSequence(@Param("executionId") String executionId);

    @Update(
            """
            update model_invocation t
               set reasoning = coalesce(reasoning, '') || #{delta},
                   reasoning_expires_at = #{expiresAt}, updated_at = #{now},
                   version = version + 1
             where t.execution_id = #{executionId}
               and t.model_invocation_sequence = #{modelInvocationSequence}
               and t.execution_attempt_number = #{executionAttemptNumber}
               and t.status = 'streaming'
               and t.version = #{expectedVersion}
               and exists (
                   select 1 from agent_execution r
                    where r.tenant_id = t.tenant_id and r.execution_id = t.execution_id
                      and r.status = 'running' and r.owner_instance_id = #{ownerInstanceId}
                      and r.execution_attempt_number = #{executionAttemptNumber})
            """)
    int appendReasoning(
            @Param("executionId") String executionId,
            @Param("modelInvocationSequence") long modelInvocationSequence,
            @Param("executionAttemptNumber") long executionAttemptNumber,
            @Param("expectedVersion") long expectedVersion,
            @Param("delta") String delta,
            @Param("expiresAt") Instant expiresAt,
            @Param("now") Instant now,
            @Param("ownerInstanceId") String ownerInstanceId);

    @Update(
            """
            update model_invocation t
               set status = #{status}, completed_at = #{now}, updated_at = #{now},
                   version = version + 1
             where t.execution_id = #{executionId}
               and t.model_invocation_sequence = #{modelInvocationSequence}
               and t.execution_attempt_number = #{executionAttemptNumber}
               and t.status = 'streaming'
               and t.version = #{expectedVersion}
               and exists (
                   select 1 from agent_execution r
                    where r.tenant_id = t.tenant_id and r.execution_id = t.execution_id
                      and r.status = 'running' and r.owner_instance_id = #{ownerInstanceId}
                      and r.execution_attempt_number = #{executionAttemptNumber})
            """)
    int finishOwned(
            @Param("executionId") String executionId,
            @Param("modelInvocationSequence") long modelInvocationSequence,
            @Param("executionAttemptNumber") long executionAttemptNumber,
            @Param("expectedVersion") long expectedVersion,
            @Param("status") String status,
            @Param("now") Instant now,
            @Param("ownerInstanceId") String ownerInstanceId);

    @Update(
            """
            update model_invocation t
               set status = 'abandoned', completed_at = #{now}, updated_at = #{now},
                   version = version + 1
             where t.execution_id = #{executionId}
               and t.model_invocation_sequence = #{modelInvocationSequence}
               and t.execution_attempt_number = #{oldExecutionAttemptNumber}
               and t.version = #{expectedVersion}
               and t.status = 'streaming'
               and exists (
                   select 1 from agent_execution r
                    where r.tenant_id = t.tenant_id and r.execution_id = t.execution_id
                      and r.status = 'running' and r.owner_instance_id = #{newOwnerInstanceId}
                      and r.execution_attempt_number = #{newExecutionAttemptNumber}
                      and r.execution_attempt_number > t.execution_attempt_number)
            """)
    int abandonRecovered(
            @Param("executionId") String executionId,
            @Param("modelInvocationSequence") long modelInvocationSequence,
            @Param("oldExecutionAttemptNumber") long oldExecutionAttemptNumber,
            @Param("expectedVersion") long expectedVersion,
            @Param("newOwnerInstanceId") String newOwnerInstanceId,
            @Param("newExecutionAttemptNumber") long newExecutionAttemptNumber,
            @Param("now") Instant now);

    /** 使用 SKIP LOCKED 分页清空终态模型调用的到期 reasoning。 */
    @InterceptorIgnore(tenantLine = "true")
    @Update(
            """
            with candidates as (
                select invocation.id
                  from model_invocation invocation
                  join agent_execution execution
                    on execution.tenant_id = invocation.tenant_id
                   and execution.execution_id = invocation.execution_id
                 where execution.status in ('done', 'failed', 'cancelled')
                   and invocation.status in ('completed', 'abandoned', 'failed')
                   and invocation.reasoning_expires_at <= #{now}
                   and coalesce(invocation.reasoning, '') <> ''
                 order by invocation.reasoning_expires_at, invocation.id
                 limit #{limit} for update of invocation skip locked
            )
            update model_invocation invocation
               set reasoning = '', reasoning_expires_at = null,
                   updated_at = current_timestamp, version = version + 1
             where invocation.id in (select id from candidates)
            """)
    int clearExpiredReasoning(@Param("now") Instant now, @Param("limit") int limit);
}
