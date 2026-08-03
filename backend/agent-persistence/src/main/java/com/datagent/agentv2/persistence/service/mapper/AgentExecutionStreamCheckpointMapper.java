package com.datagent.agentv2.persistence.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.AgentExecutionStreamCheckpointEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentExecutionStreamCheckpointMapper
        extends BaseMapper<AgentExecutionStreamCheckpointEntity> {

    @Update(
            """
            update agent_execution_stream_checkpoint s
               set execution_attempt_number = #{executionAttemptNumber},
                   model_invocation_sequence = #{modelInvocationSequence}, phase = 'CONNECTING_MODEL',
                   output_delta_sequence = 0, answer = '',
                   checkpointed_at = #{now}, updated_at = #{now}, version = version + 1
             where s.execution_id = #{executionId} and s.version = #{expectedVersion}
               and (s.execution_attempt_number < #{executionAttemptNumber}
                    or (s.execution_attempt_number = #{executionAttemptNumber}
                        and s.model_invocation_sequence < #{modelInvocationSequence}))
               and exists (
                   select 1 from agent_execution r
                    where r.tenant_id = s.tenant_id and r.execution_id = s.execution_id
                      and r.status = 'running' and r.owner_instance_id = #{ownerInstanceId}
                      and r.execution_attempt_number = #{executionAttemptNumber})
            """)
    int beginModelInvocation(
            @Param("executionId") String executionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("executionAttemptNumber") long executionAttemptNumber,
            @Param("modelInvocationSequence") long modelInvocationSequence,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("now") Instant now);

    @Update(
            """
            update agent_execution_stream_checkpoint s
               set answer = #{answer}, phase = #{phase},
                   output_delta_sequence = #{outputDeltaSequence},
                   checkpointed_at = #{now}, updated_at = #{now}, version = version + 1
             where s.execution_id = #{executionId} and s.version = #{expectedVersion}
               and s.execution_attempt_number = #{executionAttemptNumber}
               and s.model_invocation_sequence = #{modelInvocationSequence}
               and s.output_delta_sequence < #{outputDeltaSequence}
               and exists (
                   select 1 from agent_execution r
                    where r.tenant_id = s.tenant_id and r.execution_id = s.execution_id
                      and r.status = 'running' and r.owner_instance_id = #{ownerInstanceId}
                      and r.execution_attempt_number = #{executionAttemptNumber})
            """)
    int checkpoint(
            @Param("executionId") String executionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("executionAttemptNumber") long executionAttemptNumber,
            @Param("modelInvocationSequence") long modelInvocationSequence,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("outputDeltaSequence") long outputDeltaSequence,
            @Param("answer") String answer,
            @Param("phase") String phase,
            @Param("now") Instant now);

    @Update(
            """
            update agent_execution_stream_checkpoint s
               set phase = 'COMPLETED', answer = #{answer}, checkpointed_at = #{now},
                   updated_at = #{now}, version = version + 1
             where s.execution_id = #{executionId}
               and s.execution_attempt_number = #{executionAttemptNumber}
               and s.model_invocation_sequence = #{modelInvocationSequence}
               and s.version = #{expectedVersion}
               and exists (
                   select 1 from agent_execution r
                    where r.tenant_id = s.tenant_id and r.execution_id = s.execution_id
                      and r.status = 'running' and r.owner_instance_id = #{ownerInstanceId}
                      and r.execution_attempt_number = #{executionAttemptNumber})
            """)
    int complete(
            @Param("executionId") String executionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("executionAttemptNumber") long executionAttemptNumber,
            @Param("modelInvocationSequence") long modelInvocationSequence,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("answer") String answer,
            @Param("now") Instant now);
}
