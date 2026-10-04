package com.datagent.agentv2.context.hierarchical.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.context.hierarchical.persistence.entity.HierarchicalCheckpointEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** checkpoint 的 PostgreSQL 幂等插入；普通查询和 CAS 由持久化服务的 Wrapper 完成。 */
@Mapper
public interface HierarchicalCheckpointMapper extends BaseMapper<HierarchicalCheckpointEntity> {
    String INSERT_IGNORE =
            "insert into conversation_context_hierarchical_checkpoint (hierarchical_checkpoint_id, tenant_id, conversation_session_id, active_cold_id, evaluated_up_to_conversation_message_sequence, strategy_generation, created_at, created_by, updated_at, updated_by, deleted, version, covered_through_json, evaluated_through_json, policy_version, schema_version) values (#{hierarchicalCheckpointId}, #{tenantId}, #{conversationSessionId}, #{activeColdId}, #{evaluatedUpToConversationMessageSequence}, #{strategyGeneration}, #{createdAt}, #{createdBy}, #{updatedAt}, #{updatedBy}, #{deleted}, #{version}, #{coveredThroughJson}, #{evaluatedThroughJson}, #{policyVersion}, #{schemaVersion}) on conflict (tenant_id, conversation_session_id) do nothing";

    // PostgreSQL ON CONFLICT 保证唯一键竞争不污染调用方事务，通用 insert 无此语义。
    @Insert(INSERT_IGNORE)
    int insertIgnore(HierarchicalCheckpointEntity checkpoint);
}
