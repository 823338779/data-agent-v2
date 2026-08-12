package com.datagent.agentv2.context.lsm.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmCompactionJobEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 插件私有的可恢复压缩作业 SQL 映射。 */
@Mapper
public interface LsmCompactionJobMapper extends BaseMapper<LsmCompactionJobEntity> {

    String TENANT_LINE_IGNORED = "true";
    String STATUS_PLANNED = "PLANNED";
    String STATUS_GENERATING = "GENERATING";
    String STATUS_READY = "READY";
    String SELECT_RECOVERABLE_JOB =
            "select * from conversation_context_lsm_job where tenant_id = #{tenantId} and execution_id = #{executionId} and execution_attempt = #{executionAttempt} and input_up_to_message_sequence = #{inputBoundary} and status in ('"
                    + STATUS_PLANNED
                    + "', '"
                    + STATUS_GENERATING
                    + "', '"
                    + STATUS_READY
                    + "') and deleted = false";
    String SELECT_BOUNDARY_JOB =
            "select * from conversation_context_lsm_job where tenant_id = #{tenantId} and execution_id = #{executionId} and execution_attempt = #{executionAttempt} and input_up_to_message_sequence = #{inputBoundary} and deleted = false";
    String INSERT_IGNORE =
            "insert into conversation_context_lsm_job (compaction_job_id, tenant_id, conversation_session_id, execution_id, execution_attempt, input_up_to_message_sequence, source_manifest_generation, source_manifest_version, policy_snapshot, plan_document, plan_sha256, status, owner_instance_id, failure_code, completed_at, created_at, created_by, updated_at, updated_by, deleted, version) values (#{compactionJobId}, #{tenantId}, #{conversationSessionId}, #{executionId}, #{executionAttempt}, #{inputUpToMessageSequence}, #{sourceManifestGeneration}, #{sourceManifestVersion}, #{policySnapshot, typeHandler=com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler, jdbcType=OTHER}, #{planDocument, typeHandler=com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler, jdbcType=OTHER}, #{planSha256}, #{status}, #{ownerInstanceId}, #{failureCode}, #{completedAt}, #{createdAt}, #{createdBy}, #{updatedAt}, #{updatedBy}, #{deleted}, #{version}) on conflict (tenant_id, execution_id, execution_attempt, input_up_to_message_sequence) do nothing";
    String CLAIM_RECOVERABLE =
            "update conversation_context_lsm_job set owner_instance_id = #{ownerInstanceId}, status = case when status = 'PLANNED' then 'GENERATING' else status end, updated_at = #{updatedAt}, updated_by = #{updatedBy}, version = version + 1 where tenant_id = #{tenantId} and compaction_job_id = #{jobId} and execution_attempt = #{executionAttempt} and input_up_to_message_sequence = #{inputBoundary} and version = #{expectedVersion} and status in ('PLANNED', 'GENERATING', 'READY') and deleted = false";
    String TRANSITION_STATUS =
            "update conversation_context_lsm_job set status = #{targetStatus}, failure_code = #{failureCode}, completed_at = #{completedAt}, updated_at = #{updatedAt}, updated_by = #{updatedBy}, version = version + 1 where tenant_id = #{tenantId} and compaction_job_id = #{jobId} and owner_instance_id = #{ownerInstanceId} and execution_attempt = #{executionAttempt} and input_up_to_message_sequence = #{inputBoundary} and status in (${sourceStatuses}) and deleted = false";
    String REPLAN_SUPERSEDED =
            "update conversation_context_lsm_job set source_manifest_generation = #{sourceGeneration}, source_manifest_version = #{sourceVersion}, policy_snapshot = #{policySnapshot, typeHandler=com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler, jdbcType=OTHER}, plan_document = #{planDocument, typeHandler=com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler, jdbcType=OTHER}, plan_sha256 = #{planSha256}, status = 'PLANNED', owner_instance_id = #{ownerInstanceId}, failure_code = null, completed_at = null, updated_at = #{updatedAt}, updated_by = #{updatedBy}, version = version + 1 where tenant_id = #{tenantId} and compaction_job_id = #{jobId} and execution_attempt = #{executionAttempt} and input_up_to_message_sequence = #{inputBoundary} and status = 'SUPERSEDED' and deleted = false";

    /** 读取给定 execution 边界上仍可继续执行的作业。 */
    @InterceptorIgnore(tenantLine = TENANT_LINE_IGNORED)
    @Select(SELECT_RECOVERABLE_JOB)
    LsmCompactionJobEntity selectRecoverableJob(
            @Param("tenantId") String tenantId,
            @Param("executionId") String executionId,
            @Param("executionAttempt") long executionAttempt,
            @Param("inputBoundary") long inputBoundary);

    /** 读取边界上的任意状态作业，用于发布后崩溃的幂等恢复。 */
    @InterceptorIgnore(tenantLine = TENANT_LINE_IGNORED)
    @Select(SELECT_BOUNDARY_JOB)
    LsmCompactionJobEntity selectBoundaryJob(
            @Param("tenantId") String tenantId,
            @Param("executionId") String executionId,
            @Param("executionAttempt") long executionAttempt,
            @Param("inputBoundary") long inputBoundary);

    /** 依赖数据库唯一边界幂等写入首次规划。 */
    @InterceptorIgnore(tenantLine = TENANT_LINE_IGNORED)
    @Insert(INSERT_IGNORE)
    int insertIgnore(LsmCompactionJobEntity job);

    /** 当前 execution owner 以 job version 接管可恢复状态。 */
    @InterceptorIgnore(tenantLine = TENANT_LINE_IGNORED)
    @Update(CLAIM_RECOVERABLE)
    int claimRecoverable(
            @Param("tenantId") String tenantId,
            @Param("jobId") String jobId,
            @Param("executionAttempt") long executionAttempt,
            @Param("inputBoundary") long inputBoundary,
            @Param("expectedVersion") long expectedVersion,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("updatedAt") java.time.LocalDateTime updatedAt,
            @Param("updatedBy") String updatedBy);

    /** 通过 owner/attempt/input 联合 fencing 推动作业状态。 */
    @InterceptorIgnore(tenantLine = TENANT_LINE_IGNORED)
    @Update(TRANSITION_STATUS)
    int transitionStatus(
            @Param("tenantId") String tenantId,
            @Param("jobId") String jobId,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("executionAttempt") long executionAttempt,
            @Param("inputBoundary") long inputBoundary,
            @Param("sourceStatuses") String sourceStatuses,
            @Param("targetStatus") String targetStatus,
            @Param("failureCode") String failureCode,
            @Param("completedAt") java.time.LocalDateTime completedAt,
            @Param("updatedAt") java.time.LocalDateTime updatedAt,
            @Param("updatedBy") String updatedBy);

    /** CAS 冲突后在同一唯一边界 job 上安装基于新 manifest 的下一份 body-free 计划。 */
    @InterceptorIgnore(tenantLine = TENANT_LINE_IGNORED)
    @Update(REPLAN_SUPERSEDED)
    int replanSuperseded(
            @Param("tenantId") String tenantId,
            @Param("jobId") String jobId,
            @Param("executionAttempt") long executionAttempt,
            @Param("inputBoundary") long inputBoundary,
            @Param("sourceGeneration") long sourceGeneration,
            @Param("sourceVersion") long sourceVersion,
            @Param("policySnapshot") String policySnapshot,
            @Param("planDocument") String planDocument,
            @Param("planSha256") String planSha256,
            @Param("ownerInstanceId") String ownerInstanceId,
            @Param("updatedAt") java.time.LocalDateTime updatedAt,
            @Param("updatedBy") String updatedBy);
}
