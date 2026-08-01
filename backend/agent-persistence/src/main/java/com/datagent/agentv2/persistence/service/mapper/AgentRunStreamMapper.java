package com.datagent.agentv2.persistence.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.AgentRunStreamEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentRunStreamMapper extends BaseMapper<AgentRunStreamEntity> {

    @Update(
            """
            update agent_run_stream s
               set execution_attempt = #{executionAttempt},
                   model_turn_sequence = #{modelTurnSequence}, phase = 'CONNECTING_MODEL',
                   reasoning = '', answer = '', reasoning_expires_at = null,
                   checkpointed_at = #{now}, updated_at = #{now}, version = version + 1
             where s.run_id = #{runId} and s.version = #{expectedVersion}
               and exists (
                   select 1 from agent_run r
                    where r.tenant_id = s.tenant_id and r.run_id = s.run_id
                      and r.status = 'running' and r.owner_instance_id = #{ownerInstanceId}
                      and r.execution_attempt = #{executionAttempt})
            """)
    int beginTurn(
            @Param("runId") String runId,
            @Param("expectedVersion") long expectedVersion,
            @Param("executionAttempt") long executionAttempt,
            @Param("modelTurnSequence") long modelTurnSequence,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("now") Instant now);

    @Update(
            """
            update agent_run_stream s
               set reasoning = reasoning || #{reasoningDelta},
                   answer = answer || #{answerDelta}, phase = #{phase},
                   stream_sequence = stream_sequence + 1,
                   reasoning_expires_at = #{reasoningExpiresAt},
                   checkpointed_at = #{now}, updated_at = #{now}, version = version + 1
             where s.run_id = #{runId}
               and s.execution_attempt = #{executionAttempt}
               and s.model_turn_sequence = #{modelTurnSequence}
               and s.version = #{expectedVersion}
               and exists (
                   select 1 from agent_run r
                    where r.tenant_id = s.tenant_id and r.run_id = s.run_id
                      and r.status = 'running' and r.owner_instance_id = #{ownerInstanceId}
                      and r.execution_attempt = #{executionAttempt})
            """)
    int appendDelta(
            @Param("runId") String runId,
            @Param("expectedVersion") long expectedVersion,
            @Param("executionAttempt") long executionAttempt,
            @Param("modelTurnSequence") long modelTurnSequence,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("reasoningDelta") String reasoningDelta,
            @Param("answerDelta") String answerDelta,
            @Param("phase") String phase,
            @Param("reasoningExpiresAt") Instant reasoningExpiresAt,
            @Param("now") Instant now);

    @Update(
            """
            update agent_run_stream s
               set phase = 'COMPLETED', answer = #{answer}, checkpointed_at = #{now},
                   updated_at = #{now}, version = version + 1
             where s.run_id = #{runId}
               and s.execution_attempt = #{executionAttempt}
               and s.model_turn_sequence = #{modelTurnSequence}
               and s.version = #{expectedVersion}
               and exists (
                   select 1 from agent_run r
                    where r.tenant_id = s.tenant_id and r.run_id = s.run_id
                      and r.status = 'running' and r.owner_instance_id = #{ownerInstanceId}
                      and r.execution_attempt = #{executionAttempt})
            """)
    int complete(
            @Param("runId") String runId,
            @Param("expectedVersion") long expectedVersion,
            @Param("executionAttempt") long executionAttempt,
            @Param("modelTurnSequence") long modelTurnSequence,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("answer") String answer,
            @Param("now") Instant now);
}
