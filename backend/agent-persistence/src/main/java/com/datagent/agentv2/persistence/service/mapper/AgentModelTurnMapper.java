package com.datagent.agentv2.persistence.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.AgentModelTurnEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentModelTurnMapper extends BaseMapper<AgentModelTurnEntity> {

    @Select(
            """
            select coalesce(max(model_turn_sequence), 0) from agent_model_turn
             where run_id = #{runId} and is_deleted = false
            """)
    long maxSequence(@Param("runId") String runId);

    @Update(
            """
            update agent_model_turn t
               set reasoning = coalesce(reasoning, '') || #{delta},
                   reasoning_expires_at = #{expiresAt}, updated_at = #{now},
                   version = version + 1
             where t.run_id = #{runId}
               and t.model_turn_sequence = #{modelTurnSequence}
               and t.execution_attempt = #{executionAttempt}
               and t.status = 'streaming'
               and t.version = #{expectedVersion}
               and exists (
                   select 1 from agent_run r
                    where r.tenant_id = t.tenant_id and r.run_id = t.run_id
                      and r.status = 'running' and r.owner_instance_id = #{ownerInstanceId}
                      and r.execution_attempt = #{executionAttempt})
            """)
    int appendReasoning(
            @Param("runId") String runId,
            @Param("modelTurnSequence") long modelTurnSequence,
            @Param("executionAttempt") long executionAttempt,
            @Param("expectedVersion") long expectedVersion,
            @Param("delta") String delta,
            @Param("expiresAt") Instant expiresAt,
            @Param("now") Instant now,
            @Param("ownerInstanceId") String ownerInstanceId);

    @Update(
            """
            update agent_model_turn t
               set status = #{status}, completed_at = #{now}, updated_at = #{now},
                   version = version + 1
             where t.run_id = #{runId}
               and t.model_turn_sequence = #{modelTurnSequence}
               and t.execution_attempt = #{executionAttempt}
               and t.status = 'streaming'
               and t.version = #{expectedVersion}
               and exists (
                   select 1 from agent_run r
                    where r.tenant_id = t.tenant_id and r.run_id = t.run_id
                      and r.status = 'running' and r.owner_instance_id = #{ownerInstanceId}
                      and r.execution_attempt = #{executionAttempt})
            """)
    int finishOwned(
            @Param("runId") String runId,
            @Param("modelTurnSequence") long modelTurnSequence,
            @Param("executionAttempt") long executionAttempt,
            @Param("expectedVersion") long expectedVersion,
            @Param("status") String status,
            @Param("now") Instant now,
            @Param("ownerInstanceId") String ownerInstanceId);

    @Update(
            """
            update agent_model_turn t
               set status = 'abandoned', completed_at = #{now}, updated_at = #{now},
                   version = version + 1
             where t.run_id = #{runId}
               and t.model_turn_sequence = #{modelTurnSequence}
               and t.execution_attempt = #{oldExecutionAttempt}
               and t.version = #{expectedVersion}
               and t.status = 'streaming'
               and exists (
                   select 1 from agent_run r
                    where r.tenant_id = t.tenant_id and r.run_id = t.run_id
                      and r.status = 'running' and r.owner_instance_id = #{newOwnerInstanceId}
                      and r.execution_attempt = #{newExecutionAttempt}
                      and r.execution_attempt > t.execution_attempt)
            """)
    int abandonRecovered(
            @Param("runId") String runId,
            @Param("modelTurnSequence") long modelTurnSequence,
            @Param("oldExecutionAttempt") long oldExecutionAttempt,
            @Param("expectedVersion") long expectedVersion,
            @Param("newOwnerInstanceId") String newOwnerInstanceId,
            @Param("newExecutionAttempt") long newExecutionAttempt,
            @Param("now") Instant now);
}
