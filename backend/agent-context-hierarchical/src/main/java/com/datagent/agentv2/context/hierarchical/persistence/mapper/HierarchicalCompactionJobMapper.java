package com.datagent.agentv2.context.hierarchical.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.context.hierarchical.persistence.entity.HierarchicalCompactionJobEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 插件私有可恢复压缩作业 SQL 映射。 */
@Mapper
public interface HierarchicalCompactionJobMapper
        extends BaseMapper<HierarchicalCompactionJobEntity> {

    String SELECT_JOB =
            "select * from conversation_context_hierarchical_compaction_job where tenant_id = #{tenantId} and hierarchical_compaction_job_id = #{jobId} and deleted = false";
    String SELECT_RECOVERABLE =
            "select * from conversation_context_hierarchical_compaction_job where tenant_id = #{tenantId} and conversation_session_id = #{sessionId} and execution_id = #{executionId} and input_up_to_message_sequence = #{inputBoundary} and base_checkpoint_version = #{baseVersion} and base_strategy_generation = #{baseGeneration} and status in ('PLANNED', 'GENERATING', 'READY_TO_PUBLISH') and deleted = false order by execution_attempt asc limit 1";
    String SELECT_COMPLETED =
            "select * from conversation_context_hierarchical_compaction_job where tenant_id = #{tenantId} and conversation_session_id = #{sessionId} and execution_id = #{executionId} and input_up_to_message_sequence = #{inputBoundary} and base_checkpoint_version + 1 = #{checkpointVersion} and base_strategy_generation + 1 = #{checkpointGeneration} and status = 'SUCCEEDED' and deleted = false order by execution_attempt desc limit 1";
    String SELECT_BOUNDARY =
            "select * from conversation_context_hierarchical_compaction_job where tenant_id = #{tenantId} and execution_id = #{executionId} and execution_attempt = #{executionAttempt} and input_up_to_message_sequence = #{inputBoundary} and deleted = false";
    String INSERT_IGNORE =
            "insert into conversation_context_hierarchical_compaction_job (hierarchical_compaction_job_id, tenant_id, conversation_session_id, execution_id, execution_attempt, owner_instance_id, input_up_to_message_sequence, base_checkpoint_version, base_strategy_generation, plan_document, status, failure_code, completed_at, created_at, created_by, updated_at, updated_by, deleted, version) values (#{hierarchicalCompactionJobId}, #{tenantId}, #{conversationSessionId}, #{executionId}, #{executionAttempt}, #{ownerInstanceId}, #{inputUpToMessageSequence}, #{baseCheckpointVersion}, #{baseStrategyGeneration}, #{planDocument, typeHandler=com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler, jdbcType=OTHER}, #{status}, #{failureCode}, #{completedAt}, #{createdAt}, #{createdBy}, #{updatedAt}, #{updatedBy}, #{deleted}, #{version}) on conflict (tenant_id, execution_id, execution_attempt, input_up_to_message_sequence) do nothing";
    String TRANSITION_JOB =
            "update conversation_context_hierarchical_compaction_job set status = #{replacementStatus}, failure_code = #{failureCode}, completed_at = #{completedAt}, updated_at = #{updatedAt}, updated_by = #{updatedBy}, version = version + 1 where tenant_id = #{tenantId} and hierarchical_compaction_job_id = #{jobId} and conversation_session_id = #{execution.conversationSessionId} and execution_id = #{execution.executionId} and owner_instance_id = #{execution.ownerInstanceId} and execution_attempt = #{execution.executionAttempt} and input_up_to_message_sequence = #{execution.inputUpToMessageSequence} and status = #{expectedStatus} and version = #{expectedVersion} and deleted = false";
    String STORE_COLD_CANDIDATE =
            "update conversation_context_hierarchical_compaction_job set candidate_cold_document = #{candidateDocument, typeHandler=com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler, jdbcType=OTHER}, updated_at = #{updatedAt}, updated_by = #{updatedBy}, version = version + 1 where tenant_id = #{tenantId} and hierarchical_compaction_job_id = #{jobId} and conversation_session_id = #{execution.conversationSessionId} and execution_id = #{execution.executionId} and owner_instance_id = #{execution.ownerInstanceId} and execution_attempt = #{execution.executionAttempt} and input_up_to_message_sequence = #{execution.inputUpToMessageSequence} and status = 'GENERATING' and candidate_cold_document is null and version = #{expectedVersion} and deleted = false";
    String CLAIM_RECOVERABLE =
            "update conversation_context_hierarchical_compaction_job set owner_instance_id = #{execution.ownerInstanceId}, execution_attempt = #{execution.executionAttempt}, updated_at = #{updatedAt}, updated_by = #{updatedBy}, version = version + 1 where tenant_id = #{tenantId} and hierarchical_compaction_job_id = #{jobId} and conversation_session_id = #{execution.conversationSessionId} and execution_id = #{execution.executionId} and input_up_to_message_sequence = #{execution.inputUpToMessageSequence} and base_checkpoint_version = #{baseVersion} and base_strategy_generation = #{baseGeneration} and execution_attempt < #{execution.executionAttempt} and version = #{expectedVersion} and status in ('PLANNED', 'GENERATING', 'READY_TO_PUBLISH') and deleted = false and not exists (select 1 from conversation_context_hierarchical_compaction_job conflict where conflict.tenant_id = #{tenantId} and conflict.execution_id = #{execution.executionId} and conflict.execution_attempt = #{execution.executionAttempt} and conflict.input_up_to_message_sequence = #{execution.inputUpToMessageSequence} and conflict.hierarchical_compaction_job_id <> #{jobId} and conflict.deleted = false)";
    String RESET_STALE =
            "update conversation_context_hierarchical_compaction_job set base_checkpoint_version = #{newBaseVersion}, base_strategy_generation = #{newBaseGeneration}, plan_document = #{planDocument, typeHandler=com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler, jdbcType=OTHER}, status = 'PLANNED', candidate_cold_document = null, failure_code = null, completed_at = null, updated_at = #{updatedAt}, updated_by = #{updatedBy}, version = version + 1 where tenant_id = #{tenantId} and hierarchical_compaction_job_id = #{jobId} and conversation_session_id = #{execution.conversationSessionId} and execution_id = #{execution.executionId} and owner_instance_id = #{execution.ownerInstanceId} and execution_attempt = #{execution.executionAttempt} and input_up_to_message_sequence = #{execution.inputUpToMessageSequence} and base_checkpoint_version = #{oldBaseVersion} and base_strategy_generation = #{oldBaseGeneration} and version = #{expectedVersion} and status = 'READY_TO_PUBLISH' and deleted = false";

    /** 按业务标识读取当前租户作业。 */
    @Select(SELECT_JOB)
    HierarchicalCompactionJobEntity selectJob(
            @Param("tenantId") String tenantId, @Param("jobId") String jobId);

    /** 读取固定 execution 边界上的非终态作业。 */
    @Select(SELECT_RECOVERABLE)
    HierarchicalCompactionJobEntity selectRecoverable(
            @Param("tenantId") String tenantId,
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("inputBoundary") long inputBoundary,
            @Param("baseVersion") long baseVersion,
            @Param("baseGeneration") long baseGeneration);

    /** 查找已经与当前 checkpoint 一起提交的成功作业，供崩溃后幂等观察。 */
    @Select(SELECT_COMPLETED)
    HierarchicalCompactionJobEntity selectCompleted(
            @Param("tenantId") String tenantId,
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("inputBoundary") long inputBoundary,
            @Param("checkpointVersion") long checkpointVersion,
            @Param("checkpointGeneration") long checkpointGeneration);

    /** 按唯一自然键读取任意状态作业，供幂等插入冲突后返回既有记录。 */
    @Select(SELECT_BOUNDARY)
    HierarchicalCompactionJobEntity selectBoundary(
            @Param("tenantId") String tenantId,
            @Param("executionId") String executionId,
            @Param("executionAttempt") long executionAttempt,
            @Param("inputBoundary") long inputBoundary);

    /** 在唯一 execution 边界上幂等写入首次计划。 */
    @Insert(INSERT_IGNORE)
    int insertIgnore(HierarchicalCompactionJobEntity job);

    /** 以 status、version、owner、attempt 和输入边界联合推动作业状态。 */
    @Update(TRANSITION_JOB)
    int transitionJob(
            @Param("tenantId") String tenantId,
            @Param("jobId") String jobId,
            @Param("expectedStatus") String expectedStatus,
            @Param("replacementStatus") String replacementStatus,
            @Param("expectedVersion") long expectedVersion,
            @Param("execution")
                    com.datagent.agentv2.conversation.context.ContextExecutionRef execution,
            @Param("failureCode") String failureCode,
            @Param("completedAt") java.time.LocalDateTime completedAt,
            @Param("updatedAt") java.time.LocalDateTime updatedAt,
            @Param("updatedBy") String updatedBy);

    /** 仅允许 fenced GENERATING job 首次写入 durable Cold candidate。 */
    @Update(STORE_COLD_CANDIDATE)
    int storeColdCandidate(
            @Param("tenantId") String tenantId,
            @Param("jobId") String jobId,
            @Param("expectedVersion") long expectedVersion,
            @Param("execution")
                    com.datagent.agentv2.conversation.context.ContextExecutionRef execution,
            @Param("candidateDocument") String candidateDocument,
            @Param("updatedAt") java.time.LocalDateTime updatedAt,
            @Param("updatedBy") String updatedBy);

    /** 用旧版本 CAS 将同一 durable job 转交给更高 execution attempt。 */
    @Update(CLAIM_RECOVERABLE)
    int claimRecoverable(
            @Param("tenantId") String tenantId,
            @Param("jobId") String jobId,
            @Param("expectedVersion") long expectedVersion,
            @Param("baseVersion") long baseVersion,
            @Param("baseGeneration") long baseGeneration,
            @Param("execution")
                    com.datagent.agentv2.conversation.context.ContextExecutionRef execution,
            @Param("updatedAt") java.time.LocalDateTime updatedAt,
            @Param("updatedBy") String updatedBy);

    /** 将同一自然键上的 stale READY 作业原子重置为新 checkpoint 基线的 PLANNED 作业。 */
    @Update(RESET_STALE)
    int resetStale(
            @Param("tenantId") String tenantId,
            @Param("jobId") String jobId,
            @Param("expectedVersion") long expectedVersion,
            @Param("oldBaseVersion") long oldBaseVersion,
            @Param("oldBaseGeneration") long oldBaseGeneration,
            @Param("newBaseVersion") long newBaseVersion,
            @Param("newBaseGeneration") long newBaseGeneration,
            @Param("planDocument") String planDocument,
            @Param("execution")
                    com.datagent.agentv2.conversation.context.ContextExecutionRef execution,
            @Param("updatedAt") java.time.LocalDateTime updatedAt,
            @Param("updatedBy") String updatedBy);
}
