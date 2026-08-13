package com.datagent.agentv2.context.hierarchical.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.context.hierarchical.persistence.entity.HierarchicalSummaryNodeEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 插件私有不可变摘要节点 SQL 映射。 */
@Mapper
public interface HierarchicalSummaryNodeMapper extends BaseMapper<HierarchicalSummaryNodeEntity> {

    String SELECT_IDENTITY =
            "select * from conversation_context_hierarchical_summary_node where tenant_id = #{tenantId} and conversation_session_id = #{node.conversationSessionId} and level = #{node.level} and start_conversation_message_sequence = #{node.startConversationMessageSequence} and end_conversation_message_sequence = #{node.endConversationMessageSequence} and summary_schema_version = #{node.summarySchemaVersion} and summary_prompt_version = #{node.summaryPromptVersion} and model_provider = #{node.modelProvider} and model_name = #{node.modelName} and deleted = false";
    String INSERT_IGNORE =
            "insert into conversation_context_hierarchical_summary_node (hierarchical_summary_node_id, tenant_id, conversation_session_id, level, covered_completed_turn_count, start_conversation_message_sequence, end_conversation_message_sequence, left_child_node_id, right_child_node_id, summary_document, estimated_token_count, summary_schema_version, summary_prompt_version, model_provider, model_name, created_at, created_by, updated_at, updated_by, deleted, version) values (#{hierarchicalSummaryNodeId}, #{tenantId}, #{conversationSessionId}, #{level}, #{coveredCompletedTurnCount}, #{startConversationMessageSequence}, #{endConversationMessageSequence}, #{leftChildNodeId}, #{rightChildNodeId}, #{summaryDocument, typeHandler=com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler, jdbcType=OTHER}, #{estimatedTokenCount}, #{summarySchemaVersion}, #{summaryPromptVersion}, #{modelProvider}, #{modelName}, #{createdAt}, #{createdBy}, #{updatedAt}, #{updatedBy}, #{deleted}, #{version}) on conflict (tenant_id, conversation_session_id, level, start_conversation_message_sequence, end_conversation_message_sequence, summary_schema_version, summary_prompt_version, model_provider, model_name) do nothing";

    /** 按不可变节点完整 identity 读取唯一记录。 */
    @Select(SELECT_IDENTITY)
    HierarchicalSummaryNodeEntity selectIdentity(
            @Param("tenantId") String tenantId, @Param("node") HierarchicalSummaryNodeEntity node);

    /** 同 identity 并发插入时只保留首个完整节点。 */
    @Insert(INSERT_IGNORE)
    int insertIgnore(HierarchicalSummaryNodeEntity node);
}
