package com.datagent.agentv2.persistence.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.AgentExecutionEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentExecutionMapper extends BaseMapper<AgentExecutionEntity> {

    @Select("select * from agent_execution where execution_id = #{executionId} for update")
    AgentExecutionEntity selectByIdForUpdate(@Param("executionId") String executionId);

    @Select(
            """
            select owner_user_id
              from agent_execution
             where status = 'queued' and is_deleted = false
             group by owner_user_id
             order by min(queued_at), owner_user_id
             limit #{limit}
            """)
    List<String> selectQueuedOwnerUserIds(@Param("limit") int limit);

    @Select(
            """
            select count(*)
              from agent_execution
             where owner_user_id = #{userId}
               and status = 'running'
               and is_deleted = false
            """)
    int countRunningExecutionsByOwnerUserId(@Param("userId") String userId);

    @Select(
            """
            select *
              from agent_execution
             where owner_user_id = #{userId}
               and status = 'queued'
               and is_deleted = false
             order by queued_at, id
             limit 1
             for update skip locked
            """)
    AgentExecutionEntity selectOldestQueuedForUpdate(@Param("userId") String userId);

    @Update(
            """
            update agent_execution
               set status = 'running',
                   phase = 'RECEIVED',
                   execution_attempt_number = #{executionAttemptNumber},
                   owner_instance_id = #{ownerInstanceId},
                   lease_expires_at = #{leaseExpiresAt},
                   heartbeat_at = #{now},
                   active_segment_started_at = #{now},
                   started_at = coalesce(started_at, #{now}),
                   hard_deadline_at = #{hardDeadlineAt},
                   updated_at = #{now},
                   version = version + 1
             where execution_id = #{executionId}
               and status = 'queued'
               and version = #{version}
               and is_deleted = false
            """)
    int claimQueued(
            @Param("executionId") String executionId,
            @Param("version") long version,
            @Param("executionAttemptNumber") long executionAttemptNumber,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("now") Instant now,
            @Param("leaseExpiresAt") Instant leaseExpiresAt,
            @Param("hardDeadlineAt") Instant hardDeadlineAt);

    @Update(
            """
            update agent_execution
               set heartbeat_at = #{now}, lease_expires_at = #{leaseExpiresAt},
                   updated_at = #{now}, version = version + 1
             where execution_id = #{executionId} and status = 'running'
               and owner_instance_id = #{ownerInstanceId}
               and execution_attempt_number = #{executionAttemptNumber}
               and hard_deadline_at > #{now} and is_deleted = false
            """)
    int renewLease(
            @Param("executionId") String executionId,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("executionAttemptNumber") long executionAttemptNumber,
            @Param("now") Instant now,
            @Param("leaseExpiresAt") Instant leaseExpiresAt);

    @Update(
            """
            update agent_execution set updated_at = updated_at
             where execution_id = #{executionId} and status = 'running'
               and owner_instance_id = #{ownerInstanceId}
               and execution_attempt_number = #{executionAttemptNumber}
               and hard_deadline_at > #{now} and is_deleted = false
            """)
    int fenceOwned(
            @Param("executionId") String executionId,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("executionAttemptNumber") long executionAttemptNumber,
            @Param("now") Instant now);

    @Select(
            """
            select count(*) from agent_execution
             where execution_id = #{executionId} and status = 'running'
               and owner_instance_id = #{ownerInstanceId}
               and execution_attempt_number = #{executionAttemptNumber}
               and cancellation_requested_at is not null and is_deleted = false
            """)
    int countCancellationRequested(
            @Param("executionId") String executionId,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("executionAttemptNumber") long executionAttemptNumber);

    @Update(
            """
            update agent_execution
               set status = 'cancelled', completed_at = #{now},
                   active_execution_ms = active_execution_ms + greatest(0,
                       (extract(epoch from (#{now} - active_segment_started_at)) * 1000)::bigint),
                   owner_instance_id = null, active_segment_started_at = null,
                   heartbeat_at = null, lease_expires_at = null, hard_deadline_at = null,
                   updated_at = #{now}, version = version + 1
             where execution_id = #{executionId} and status = 'running'
               and owner_instance_id = #{ownerInstanceId}
               and execution_attempt_number = #{executionAttemptNumber}
               and cancellation_requested_at is not null and is_deleted = false
            """)
    int cancelOwnedIfRequested(
            @Param("executionId") String executionId,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("executionAttemptNumber") long executionAttemptNumber,
            @Param("now") Instant now);

    @Update(
            """
            update agent_execution
               set status = 'cancelled', completed_at = #{now},
                   owner_instance_id = null, active_segment_started_at = null,
                   heartbeat_at = null, lease_expires_at = null, hard_deadline_at = null,
                   updated_at = #{now}, version = version + 1
             where execution_id = #{executionId} and version = #{version}
               and status in ('queued', 'waiting_for_user', 'waiting_confirmation')
               and is_deleted = false
            """)
    int cancelLocked(
            @Param("executionId") String executionId,
            @Param("version") long version,
            @Param("now") Instant now);

    @Update(
            """
            update agent_execution
               set status = 'failed', phase = 'FAILED', failure_code = #{failureCode},
                   failure_detail = #{failureDetail}, failed_at = #{now},
                   active_execution_ms = active_execution_ms + greatest(0,
                       (extract(epoch from (#{now} - active_segment_started_at)) * 1000)::bigint),
                   owner_instance_id = null, active_segment_started_at = null,
                   heartbeat_at = null, lease_expires_at = null, hard_deadline_at = null,
                   updated_at = #{now}, version = version + 1
             where execution_id = #{executionId} and status = 'running'
               and owner_instance_id = #{ownerInstanceId}
               and execution_attempt_number = #{executionAttemptNumber}
               and is_deleted = false
            """)
    int failOwned(
            @Param("executionId") String executionId,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("executionAttemptNumber") long executionAttemptNumber,
            @Param("failureCode") String failureCode,
            @Param("failureDetail") String failureDetail,
            @Param("now") Instant now);

    @Update(
            """
            update agent_execution
               set status = 'failed', phase = 'FAILED', failure_code = #{failureCode},
                   failure_detail = #{failureDetail}, failed_at = #{now},
                   active_execution_ms = #{activeExecutionMs},
                   owner_instance_id = null, active_segment_started_at = null,
                   heartbeat_at = null, lease_expires_at = null, hard_deadline_at = null,
                   updated_at = #{now}, version = version + 1
             where execution_id = #{executionId} and version = #{version}
               and status = 'running' and owner_instance_id = #{ownerInstanceId}
               and execution_attempt_number = #{executionAttemptNumber}
               and is_deleted = false
            """)
    int failLocked(
            @Param("executionId") String executionId,
            @Param("version") long version,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("executionAttemptNumber") long executionAttemptNumber,
            @Param("failureCode") String failureCode,
            @Param("failureDetail") String failureDetail,
            @Param("activeExecutionMs") long activeExecutionMs,
            @Param("now") Instant now);

    @Update(
            """
            update agent_execution
               set status = 'failed', phase = 'FAILED', failure_code = 'EXECUTION_DEADLINE_EXCEEDED',
                   failed_at = #{now},
                   active_execution_ms = active_execution_ms + greatest(0,
                       (extract(epoch from (#{now} - active_segment_started_at)) * 1000)::bigint),
                   owner_instance_id = null, active_segment_started_at = null,
                   heartbeat_at = null, lease_expires_at = null, hard_deadline_at = null,
                   updated_at = #{now}, version = version + 1
             where execution_id = #{executionId} and status = 'running'
               and owner_instance_id = #{ownerInstanceId}
               and execution_attempt_number = #{executionAttemptNumber}
               and hard_deadline_at <= #{now} and is_deleted = false
            """)
    int failOwnedPastDeadline(
            @Param("executionId") String executionId,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("executionAttemptNumber") long executionAttemptNumber,
            @Param("now") Instant now);

    @Select(
            """
            select * from agent_execution
             where status = 'running' and lease_expires_at <= #{now} and is_deleted = false
             order by lease_expires_at, id limit 1 for update skip locked
            """)
    AgentExecutionEntity selectExpiredForUpdate(@Param("now") Instant now);

    @Update(
            """
            update agent_execution
               set owner_instance_id = #{newOwnerInstanceId},
                   execution_attempt_number = #{newExecutionAttemptNumber},
                   recovery_attempt_count = recovery_attempt_count + 1,
                   active_execution_ms = #{activeExecutionMs},
                   active_segment_started_at = #{now},
                   heartbeat_at = #{now}, lease_expires_at = #{leaseExpiresAt},
                   hard_deadline_at = #{hardDeadlineAt}, updated_at = #{now},
                   version = version + 1
             where execution_id = #{executionId} and status = 'running'
               and owner_instance_id = #{oldOwnerInstanceId}
               and execution_attempt_number = #{oldExecutionAttemptNumber}
               and version = #{version} and lease_expires_at <= #{now}
               and is_deleted = false
            """)
    int takeoverExpired(
            @Param("executionId") String executionId,
            @Param("version") long version,
            @Param("oldOwnerInstanceId") String oldOwnerInstanceId,
            @Param("oldExecutionAttemptNumber") long oldExecutionAttemptNumber,
            @Param("newOwnerInstanceId") String newOwnerInstanceId,
            @Param("newExecutionAttemptNumber") long newExecutionAttemptNumber,
            @Param("activeExecutionMs") long activeExecutionMs,
            @Param("now") Instant now,
            @Param("leaseExpiresAt") Instant leaseExpiresAt,
            @Param("hardDeadlineAt") Instant hardDeadlineAt);

    @Update(
            """
            update agent_execution
               set status = 'failed', phase = 'FAILED', failure_code = 'EXECUTION_DEADLINE_EXCEEDED',
                   failed_at = #{now}, active_execution_ms = #{activeExecutionMs},
                   owner_instance_id = null, active_segment_started_at = null,
                   heartbeat_at = null, lease_expires_at = null, hard_deadline_at = null,
                   updated_at = #{now}, version = version + 1
             where execution_id = #{executionId} and status = 'running'
               and owner_instance_id = #{ownerInstanceId}
               and execution_attempt_number = #{executionAttemptNumber}
               and version = #{version} and is_deleted = false
            """)
    int failExpiredBudget(
            @Param("executionId") String executionId,
            @Param("version") long version,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("executionAttemptNumber") long executionAttemptNumber,
            @Param("activeExecutionMs") long activeExecutionMs,
            @Param("now") Instant now);

    @Update(
            """
            update agent_execution
               set status = 'done', phase = 'COMPLETED', completed_at = #{now},
                   active_execution_ms = active_execution_ms + greatest(0,
                       (extract(epoch from (#{now} - active_segment_started_at)) * 1000)::bigint),
                   owner_instance_id = null, active_segment_started_at = null,
                   heartbeat_at = null, lease_expires_at = null, hard_deadline_at = null,
                   updated_at = #{now}, version = version + 1
             where execution_id = #{executionId} and status = 'running'
               and owner_instance_id = #{ownerInstanceId}
               and execution_attempt_number = #{executionAttemptNumber}
               and is_deleted = false
            """)
    int completeOwned(
            @Param("executionId") String executionId,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("executionAttemptNumber") long executionAttemptNumber,
            @Param("now") Instant now);

    @Update(
            """
            update agent_execution
               set status = 'failed', phase = 'FAILED', failure_code = 'RECOVERY_EXHAUSTED',
                   failed_at = #{now}, active_execution_ms = #{activeExecutionMs},
                   owner_instance_id = null, active_segment_started_at = null,
                   heartbeat_at = null, lease_expires_at = null, hard_deadline_at = null,
                   updated_at = #{now}, version = version + 1
             where execution_id = #{executionId} and status = 'running'
               and owner_instance_id = #{ownerInstanceId}
               and execution_attempt_number = #{executionAttemptNumber}
               and version = #{version} and lease_expires_at <= #{now}
               and is_deleted = false
            """)
    int failExpiredRecovery(
            @Param("executionId") String executionId,
            @Param("version") long version,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("executionAttemptNumber") long executionAttemptNumber,
            @Param("activeExecutionMs") long activeExecutionMs,
            @Param("now") Instant now);
}
