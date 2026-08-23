package com.datagent.agentv2.context.hierarchical.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.context.hierarchical.persistence.entity.HierarchicalCheckpointEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 通过 job fence 原子切换会话唯一 active Cold。 */
@Mapper
public interface HierarchicalCheckpointMapper extends BaseMapper<HierarchicalCheckpointEntity> {
    String SELECT_CHECKPOINT =
            "select * from conversation_context_hierarchical_checkpoint where tenant_id = #{tenantId} and conversation_session_id = #{sessionId} and deleted = false";
    String INSERT_IGNORE =
            "insert into conversation_context_hierarchical_checkpoint (hierarchical_checkpoint_id, tenant_id, conversation_session_id, active_cold_id, evaluated_up_to_conversation_message_sequence, strategy_generation, created_at, created_by, updated_at, updated_by, deleted, version) values (#{hierarchicalCheckpointId}, #{tenantId}, #{conversationSessionId}, #{activeColdId}, #{evaluatedUpToConversationMessageSequence}, #{strategyGeneration}, #{createdAt}, #{createdBy}, #{updatedAt}, #{updatedBy}, #{deleted}, #{version}) on conflict (tenant_id, conversation_session_id) do nothing";
    String COMPARE_AND_SET =
            "update conversation_context_hierarchical_checkpoint set active_cold_id = #{replacement.activeColdId}, evaluated_up_to_conversation_message_sequence = #{replacement.evaluatedUpToConversationMessageSequence}, strategy_generation = #{replacement.strategyGeneration}, updated_at = #{replacement.updatedAt}, updated_by = #{replacement.updatedBy}, version = version + 1 where tenant_id = #{tenantId} and conversation_session_id = #{expected.conversationSessionId} and version = #{expected.version} and strategy_generation = #{expected.strategyGeneration} and evaluated_up_to_conversation_message_sequence = #{expected.evaluatedUpToConversationMessageSequence} and evaluated_up_to_conversation_message_sequence <= #{replacement.evaluatedUpToConversationMessageSequence} and strategy_generation < #{replacement.strategyGeneration} and deleted = false";

    @Select(SELECT_CHECKPOINT)
    HierarchicalCheckpointEntity selectCheckpoint(
            @Param("tenantId") String tenantId, @Param("sessionId") String sessionId);

    @Insert(INSERT_IGNORE)
    int insertIgnore(HierarchicalCheckpointEntity checkpoint);

    @Update(COMPARE_AND_SET)
    int compareAndSet(
            @Param("tenantId") String tenantId,
            @Param("expected") HierarchicalCheckpointEntity expected,
            @Param("replacement") HierarchicalCheckpointEntity replacement);
}
