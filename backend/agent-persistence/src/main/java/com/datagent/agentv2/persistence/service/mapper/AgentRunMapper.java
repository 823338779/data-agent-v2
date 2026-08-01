package com.datagent.agentv2.persistence.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.persistence.entity.AgentRunEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRunEntity> {

    @Select("select * from agent_run where run_id = #{runId} for update")
    AgentRunEntity selectByIdForUpdate(@Param("runId") String runId);

    @Select(
            """
            select owner_user_id
              from agent_run
             where status = 'queued' and is_deleted = false
             group by owner_user_id
             order by min(queued_at), owner_user_id
             limit #{limit}
            """)
    List<String> selectQueuedOwnerUserIds(@Param("limit") int limit);

    @Select(
            """
            select count(*)
              from agent_run
             where owner_user_id = #{userId}
               and status = 'running'
               and is_deleted = false
            """)
    int countRunningByOwnerUserId(@Param("userId") String userId);

    @Select(
            """
            select *
              from agent_run
             where owner_user_id = #{userId}
               and status = 'queued'
               and is_deleted = false
             order by queued_at, id
             limit 1
             for update skip locked
            """)
    AgentRunEntity selectOldestQueuedForUpdate(@Param("userId") String userId);

    @Update(
            """
            update agent_run
               set status = 'running',
                   phase = 'RECEIVED',
                   execution_attempt = #{executionAttempt},
                   owner_instance_id = #{ownerInstanceId},
                   lease_expires_at = #{leaseExpiresAt},
                   heartbeat_at = #{now},
                   active_segment_started_at = #{now},
                   started_at = coalesce(started_at, #{now}),
                   hard_deadline_at = #{hardDeadlineAt},
                   updated_at = #{now},
                   version = version + 1
             where run_id = #{runId}
               and status = 'queued'
               and version = #{version}
               and is_deleted = false
            """)
    int claimQueued(
            @Param("runId") String runId,
            @Param("version") long version,
            @Param("executionAttempt") long executionAttempt,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("now") Instant now,
            @Param("leaseExpiresAt") Instant leaseExpiresAt,
            @Param("hardDeadlineAt") Instant hardDeadlineAt);

    @Update(
            """
            update agent_run
               set heartbeat_at = #{now}, lease_expires_at = #{leaseExpiresAt},
                   updated_at = #{now}, version = version + 1
             where run_id = #{runId} and status = 'running'
               and owner_instance_id = #{ownerInstanceId}
               and execution_attempt = #{executionAttempt}
               and hard_deadline_at > #{now} and is_deleted = false
            """)
    int renewLease(
            @Param("runId") String runId,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("executionAttempt") long executionAttempt,
            @Param("now") Instant now,
            @Param("leaseExpiresAt") Instant leaseExpiresAt);

    @Update(
            """
            update agent_run set updated_at = updated_at
             where run_id = #{runId} and status = 'running'
               and owner_instance_id = #{ownerInstanceId}
               and execution_attempt = #{executionAttempt}
               and hard_deadline_at > #{now} and is_deleted = false
            """)
    int fenceOwned(
            @Param("runId") String runId,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("executionAttempt") long executionAttempt,
            @Param("now") Instant now);

    @Select(
            """
            select count(*) from agent_run
             where run_id = #{runId} and status = 'running'
               and owner_instance_id = #{ownerInstanceId}
               and execution_attempt = #{executionAttempt}
               and cancellation_requested_at is not null and is_deleted = false
            """)
    int countCancellationRequested(
            @Param("runId") String runId,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("executionAttempt") long executionAttempt);

    @Update(
            """
            update agent_run
               set status = 'cancelled', completed_at = #{now},
                   active_execution_ms = active_execution_ms + greatest(0,
                       (extract(epoch from (#{now} - active_segment_started_at)) * 1000)::bigint),
                   owner_instance_id = null, active_segment_started_at = null,
                   heartbeat_at = null, lease_expires_at = null, hard_deadline_at = null,
                   updated_at = #{now}, version = version + 1
             where run_id = #{runId} and status = 'running'
               and owner_instance_id = #{ownerInstanceId}
               and execution_attempt = #{executionAttempt}
               and cancellation_requested_at is not null and is_deleted = false
            """)
    int cancelOwnedIfRequested(
            @Param("runId") String runId,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("executionAttempt") long executionAttempt,
            @Param("now") Instant now);

    @Update(
            """
            update agent_run
               set status = 'failed', phase = 'FAILED', failure_code = 'RUN_DEADLINE_EXCEEDED',
                   failed_at = #{now},
                   active_execution_ms = active_execution_ms + greatest(0,
                       (extract(epoch from (#{now} - active_segment_started_at)) * 1000)::bigint),
                   owner_instance_id = null, active_segment_started_at = null,
                   heartbeat_at = null, lease_expires_at = null, hard_deadline_at = null,
                   updated_at = #{now}, version = version + 1
             where run_id = #{runId} and status = 'running'
               and owner_instance_id = #{ownerInstanceId}
               and execution_attempt = #{executionAttempt}
               and hard_deadline_at <= #{now} and is_deleted = false
            """)
    int failOwnedPastDeadline(
            @Param("runId") String runId,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("executionAttempt") long executionAttempt,
            @Param("now") Instant now);

    @Select(
            """
            select * from agent_run
             where status = 'running' and lease_expires_at <= #{now} and is_deleted = false
             order by lease_expires_at, id limit 1 for update skip locked
            """)
    AgentRunEntity selectExpiredForUpdate(@Param("now") Instant now);

    @Update(
            """
            update agent_run
               set owner_instance_id = #{newOwnerInstanceId},
                   execution_attempt = #{newExecutionAttempt},
                   recovery_attempt_count = recovery_attempt_count + 1,
                   active_execution_ms = #{activeExecutionMs},
                   active_segment_started_at = #{now},
                   heartbeat_at = #{now}, lease_expires_at = #{leaseExpiresAt},
                   hard_deadline_at = #{hardDeadlineAt}, updated_at = #{now},
                   version = version + 1
             where run_id = #{runId} and status = 'running'
               and owner_instance_id = #{oldOwnerInstanceId}
               and execution_attempt = #{oldExecutionAttempt}
               and version = #{version} and lease_expires_at <= #{now}
               and is_deleted = false
            """)
    int takeoverExpired(
            @Param("runId") String runId,
            @Param("version") long version,
            @Param("oldOwnerInstanceId") String oldOwnerInstanceId,
            @Param("oldExecutionAttempt") long oldExecutionAttempt,
            @Param("newOwnerInstanceId") String newOwnerInstanceId,
            @Param("newExecutionAttempt") long newExecutionAttempt,
            @Param("activeExecutionMs") long activeExecutionMs,
            @Param("now") Instant now,
            @Param("leaseExpiresAt") Instant leaseExpiresAt,
            @Param("hardDeadlineAt") Instant hardDeadlineAt);

    @Update(
            """
            update agent_run
               set status = 'failed', phase = 'FAILED', failure_code = 'RUN_DEADLINE_EXCEEDED',
                   failed_at = #{now}, active_execution_ms = #{activeExecutionMs},
                   owner_instance_id = null, active_segment_started_at = null,
                   heartbeat_at = null, lease_expires_at = null, hard_deadline_at = null,
                   updated_at = #{now}, version = version + 1
             where run_id = #{runId} and status = 'running'
               and owner_instance_id = #{ownerInstanceId}
               and execution_attempt = #{executionAttempt}
               and version = #{version} and is_deleted = false
            """)
    int failExpiredBudget(
            @Param("runId") String runId,
            @Param("version") long version,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("executionAttempt") long executionAttempt,
            @Param("activeExecutionMs") long activeExecutionMs,
            @Param("now") Instant now);

    @Update(
            """
            update agent_run
               set status = 'done', phase = 'COMPLETED', completed_at = #{now},
                   active_execution_ms = active_execution_ms + greatest(0,
                       (extract(epoch from (#{now} - active_segment_started_at)) * 1000)::bigint),
                   owner_instance_id = null, active_segment_started_at = null,
                   heartbeat_at = null, lease_expires_at = null, hard_deadline_at = null,
                   updated_at = #{now}, version = version + 1
             where run_id = #{runId} and status = 'running'
               and owner_instance_id = #{ownerInstanceId}
               and execution_attempt = #{executionAttempt}
               and is_deleted = false
            """)
    int completeOwned(
            @Param("runId") String runId,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("executionAttempt") long executionAttempt,
            @Param("now") Instant now);

    @Update(
            """
            update agent_run
               set status = 'failed', phase = 'FAILED', failure_code = 'RECOVERY_EXHAUSTED',
                   failed_at = #{now}, active_execution_ms = #{activeExecutionMs},
                   owner_instance_id = null, active_segment_started_at = null,
                   heartbeat_at = null, lease_expires_at = null, hard_deadline_at = null,
                   updated_at = #{now}, version = version + 1
             where run_id = #{runId} and status = 'running'
               and owner_instance_id = #{ownerInstanceId}
               and execution_attempt = #{executionAttempt}
               and version = #{version} and lease_expires_at <= #{now}
               and is_deleted = false
            """)
    int failExpiredRecovery(
            @Param("runId") String runId,
            @Param("version") long version,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("executionAttempt") long executionAttempt,
            @Param("activeExecutionMs") long activeExecutionMs,
            @Param("now") Instant now);
}
