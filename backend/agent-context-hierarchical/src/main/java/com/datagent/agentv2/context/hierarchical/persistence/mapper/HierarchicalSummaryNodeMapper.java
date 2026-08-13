package com.datagent.agentv2.context.hierarchical.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.context.hierarchical.persistence.entity.HierarchicalSummaryNodeEntity;
import java.util.List;
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

    /** frontier ID 读取同时固定当前 tenant 与目标 conversation session。 */
    String SELECT_BY_IDS =
            "<script>select * from conversation_context_hierarchical_summary_node where tenant_id = #{tenantId} and conversation_session_id = #{conversationSessionId} and hierarchical_summary_node_id in <foreach item='nodeId' collection='nodeIds' open='(' separator=',' close=')'>#{nodeId}</foreach> and deleted = false</script>";

    /** 按不可变节点完整 identity 读取唯一记录。 */
    @Select(SELECT_IDENTITY)
    HierarchicalSummaryNodeEntity selectIdentity(
            @Param("tenantId") String tenantId, @Param("node") HierarchicalSummaryNodeEntity node);

    /** 在租户和会话双重边界内批量读取正式 frontier 引用的节点。 */
    @Select(SELECT_BY_IDS)
    List<HierarchicalSummaryNodeEntity> selectNodesByIds(
            @Param("tenantId") String tenantId,
            @Param("conversationSessionId") String conversationSessionId,
            @Param("nodeIds") List<String> nodeIds);

    /** 同 identity 并发插入时只保留首个完整节点。 */
    @Insert(INSERT_IGNORE)
    int insertIgnore(HierarchicalSummaryNodeEntity node);
}
