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
            "select * from conversation_context_hierarchical_compaction_job where tenant_id = #{tenantId} and execution_id = #{executionId} and execution_attempt = #{executionAttempt} and input_up_to_message_sequence = #{inputBoundary} and status in ('PLANNED', 'GENERATING', 'READY_TO_PUBLISH') and deleted = false";
    String SELECT_BOUNDARY =
            "select * from conversation_context_hierarchical_compaction_job where tenant_id = #{tenantId} and execution_id = #{executionId} and execution_attempt = #{executionAttempt} and input_up_to_message_sequence = #{inputBoundary} and deleted = false";
    String INSERT_IGNORE =
            "insert into conversation_context_hierarchical_compaction_job (hierarchical_compaction_job_id, tenant_id, conversation_session_id, execution_id, execution_attempt, owner_instance_id, input_up_to_message_sequence, base_checkpoint_version, base_strategy_generation, plan_document, status, failure_code, completed_at, created_at, created_by, updated_at, updated_by, deleted, version) values (#{hierarchicalCompactionJobId}, #{tenantId}, #{conversationSessionId}, #{executionId}, #{executionAttempt}, #{ownerInstanceId}, #{inputUpToMessageSequence}, #{baseCheckpointVersion}, #{baseStrategyGeneration}, #{planDocument, typeHandler=com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler, jdbcType=OTHER}, #{status}, #{failureCode}, #{completedAt}, #{createdAt}, #{createdBy}, #{updatedAt}, #{updatedBy}, #{deleted}, #{version}) on conflict (tenant_id, execution_id, execution_attempt, input_up_to_message_sequence) do nothing";
    String TRANSITION_JOB =
            "update conversation_context_hierarchical_compaction_job set status = #{replacementStatus}, failure_code = #{failureCode}, completed_at = #{completedAt}, updated_at = #{updatedAt}, updated_by = #{updatedBy}, version = version + 1 where tenant_id = #{tenantId} and hierarchical_compaction_job_id = #{jobId} and conversation_session_id = #{execution.conversationSessionId} and execution_id = #{execution.executionId} and owner_instance_id = #{execution.ownerInstanceId} and execution_attempt = #{execution.executionAttempt} and input_up_to_message_sequence = #{execution.inputUpToMessageSequence} and status = #{expectedStatus} and version = #{expectedVersion} and deleted = false";

    /** 按业务标识读取当前租户作业。 */
    @Select(SELECT_JOB)
    HierarchicalCompactionJobEntity selectJob(
            @Param("tenantId") String tenantId, @Param("jobId") String jobId);

    /** 读取固定 execution 边界上的非终态作业。 */
    @Select(SELECT_RECOVERABLE)
    HierarchicalCompactionJobEntity selectRecoverable(
            @Param("tenantId") String tenantId,
            @Param("executionId") String executionId,
            @Param("executionAttempt") long executionAttempt,
            @Param("inputBoundary") long inputBoundary);

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
}
