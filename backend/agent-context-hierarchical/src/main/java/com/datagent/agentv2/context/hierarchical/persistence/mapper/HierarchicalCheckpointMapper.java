package com.datagent.agentv2.context.hierarchical.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.context.hierarchical.persistence.entity.HierarchicalCheckpointEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 插件私有 checkpoint SQL 映射，通过联合 CAS 原子发布全部可见状态。 */
@Mapper
public interface HierarchicalCheckpointMapper extends BaseMapper<HierarchicalCheckpointEntity> {

    String STATUS_READY_TO_PUBLISH = "READY_TO_PUBLISH";
    String SELECT_CHECKPOINT =
            "select * from conversation_context_hierarchical_checkpoint where tenant_id = #{tenantId} and conversation_session_id = #{sessionId} and deleted = false";
    String INSERT_IGNORE =
            "insert into conversation_context_hierarchical_checkpoint (hierarchical_checkpoint_id, tenant_id, conversation_session_id, cold_summary_document, summarized_up_to_conversation_message_sequence, summarized_completed_turn_count, estimated_token_count, summary_schema_version, summary_prompt_version, model_provider, model_name, active_frontier_document, strategy_generation, created_at, created_by, updated_at, updated_by, deleted, version) values (#{hierarchicalCheckpointId}, #{tenantId}, #{conversationSessionId}, #{coldSummaryDocument, typeHandler=com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler, jdbcType=OTHER}, #{summarizedUpToConversationMessageSequence}, #{summarizedCompletedTurnCount}, #{estimatedTokenCount}, #{summarySchemaVersion}, #{summaryPromptVersion}, #{modelProvider}, #{modelName}, #{activeFrontierDocument, typeHandler=com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler, jdbcType=OTHER}, #{strategyGeneration}, #{createdAt}, #{createdBy}, #{updatedAt}, #{updatedBy}, #{deleted}, #{version}) on conflict (tenant_id, conversation_session_id) do nothing";
    String COMPARE_AND_SET =
            "update conversation_context_hierarchical_checkpoint set cold_summary_document = #{replacement.coldSummaryDocument, typeHandler=com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler, jdbcType=OTHER}, summarized_up_to_conversation_message_sequence = #{replacement.summarizedUpToConversationMessageSequence}, summarized_completed_turn_count = #{replacement.summarizedCompletedTurnCount}, estimated_token_count = #{replacement.estimatedTokenCount}, summary_schema_version = #{replacement.summarySchemaVersion}, summary_prompt_version = #{replacement.summaryPromptVersion}, model_provider = #{replacement.modelProvider}, model_name = #{replacement.modelName}, active_frontier_document = #{replacement.activeFrontierDocument, typeHandler=com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler, jdbcType=OTHER}, strategy_generation = #{replacement.strategyGeneration}, updated_at = #{replacement.updatedAt}, updated_by = #{replacement.updatedBy}, version = version + 1 where tenant_id = #{tenantId} and conversation_session_id = #{execution.conversationSessionId} and version = #{expected.version} and strategy_generation = #{expected.strategyGeneration} and summarized_up_to_conversation_message_sequence = #{expected.summarizedUpToConversationMessageSequence} and summarized_up_to_conversation_message_sequence <= #{replacement.summarizedUpToConversationMessageSequence} and #{replacement.summarizedUpToConversationMessageSequence} <= #{execution.inputUpToMessageSequence} and strategy_generation < #{replacement.strategyGeneration} and deleted = false and exists (select 1 from conversation_context_hierarchical_compaction_job job where job.tenant_id = #{tenantId} and job.hierarchical_compaction_job_id = #{jobId} and job.conversation_session_id = #{execution.conversationSessionId} and job.execution_id = #{execution.executionId} and job.owner_instance_id = #{execution.ownerInstanceId} and job.execution_attempt = #{execution.executionAttempt} and job.input_up_to_message_sequence = #{execution.inputUpToMessageSequence} and job.base_checkpoint_version = #{expected.version} and job.base_strategy_generation = #{expected.strategyGeneration} and job.status = '"
                    + STATUS_READY_TO_PUBLISH
                    + "' and job.deleted = false)";
    String SELECT_READY_JOB_ID =
            "select hierarchical_compaction_job_id from conversation_context_hierarchical_compaction_job where tenant_id = #{tenantId} and conversation_session_id = #{execution.conversationSessionId} and execution_id = #{execution.executionId} and owner_instance_id = #{execution.ownerInstanceId} and execution_attempt = #{execution.executionAttempt} and input_up_to_message_sequence = #{execution.inputUpToMessageSequence} and base_checkpoint_version = #{baseVersion} and base_strategy_generation = #{baseGeneration} and status = 'READY_TO_PUBLISH' and deleted = false limit 1";

    /** 读取当前租户会话唯一的有效 checkpoint。 */
    @Select(SELECT_CHECKPOINT)
    HierarchicalCheckpointEntity selectCheckpoint(
            @Param("tenantId") String tenantId, @Param("sessionId") String sessionId);

    /** 幂等创建会话初始 checkpoint。 */
    @Insert(INSERT_IGNORE)
    int insertIgnore(HierarchicalCheckpointEntity checkpoint);

    /** 通过 version、generation、单调指针和 execution job fencing 原子发布 replacement。 */
    @Update(COMPARE_AND_SET)
    int compareAndSet(
            @Param("tenantId") String tenantId,
            @Param("expected") HierarchicalCheckpointEntity expected,
            @Param("replacement") HierarchicalCheckpointEntity replacement,
            @Param("jobId") String jobId,
            @Param("execution")
                    com.datagent.agentv2.conversation.context.ContextExecutionRef execution);

    /** 仅供旧仓储 API 兼容查找其唯一 READY job；正式发布显式传 jobId。 */
    @Select(SELECT_READY_JOB_ID)
    String selectReadyJobId(
            @Param("tenantId") String tenantId,
            @Param("execution")
                    com.datagent.agentv2.conversation.context.ContextExecutionRef execution,
            @Param("baseVersion") long baseVersion,
            @Param("baseGeneration") long baseGeneration);
}
