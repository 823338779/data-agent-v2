package com.datagent.agentv2.context.hierarchical.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.context.hierarchical.persistence.entity.HierarchicalColdEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 不可变滚动 Cold 的租户隔离、幂等持久化映射。 */
@Mapper
public interface HierarchicalColdMapper extends BaseMapper<HierarchicalColdEntity> {
    @InterceptorIgnore(tenantLine = "true")
    @Select(
            "select * from conversation_context_hierarchical_cold where tenant_id = #{tenantId} and conversation_session_id = #{sessionId} and hierarchical_cold_id = #{coldId} and deleted = false")
    HierarchicalColdEntity selectCold(
            @Param("tenantId") String tenantId,
            @Param("sessionId") String sessionId,
            @Param("coldId") String coldId);

    @InterceptorIgnore(tenantLine = "true")
    @Insert(
            "insert into conversation_context_hierarchical_cold (hierarchical_cold_id, tenant_id, conversation_session_id, start_turn_sequence, end_turn_sequence, start_message_sequence, end_message_sequence, summary_document, persisted_token_count, rendered_token_count, target_rendered_tokens, summary_schema_version, summary_prompt_version, model_provider, model_name, source_content_sha256, created_at, created_by, updated_at, updated_by, deleted, version) values (#{cold.hierarchicalColdId}, #{cold.tenantId}, #{cold.conversationSessionId}, #{cold.startTurnSequence}, #{cold.endTurnSequence}, #{cold.startMessageSequence}, #{cold.endMessageSequence}, #{cold.summaryDocument, typeHandler=com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler, jdbcType=OTHER}, #{cold.persistedTokenCount}, #{cold.renderedTokenCount}, #{cold.targetRenderedTokens}, #{cold.summarySchemaVersion}, #{cold.summaryPromptVersion}, #{cold.modelProvider}, #{cold.modelName}, #{cold.sourceContentSha256}, #{cold.createdAt}, #{cold.createdBy}, #{cold.updatedAt}, #{cold.updatedBy}, #{cold.deleted}, #{cold.version}) on conflict (hierarchical_cold_id) do nothing")
    int insertIgnore(@Param("cold") HierarchicalColdEntity cold);
}
