package com.datagent.agentv2.context.lsm.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmContextManifestEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** 插件私有的会话 manifest SQL 映射；CAS 保持一行会话视图的原子发布。 */
@Mapper
public interface LsmContextManifestMapper extends BaseMapper<LsmContextManifestEntity> {

    String TENANT_LINE_IGNORED = "true";
    String INSERT_IGNORE =
            "insert into conversation_context_lsm_manifest (context_manifest_id, tenant_id, conversation_session_id, active_segment_ids, hot_from_turn_sequence, evaluated_up_to_message_sequence, effective_policy_version, effective_fanout, effective_max_level, generation, created_at, created_by, updated_at, updated_by, deleted, version) values (#{contextManifestId}, #{tenantId}, #{conversationSessionId}, #{activeSegmentIds, typeHandler=com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler, jdbcType=OTHER}, #{hotFromTurnSequence}, #{evaluatedUpToMessageSequence}, #{effectivePolicyVersion}, #{effectiveFanout}, #{effectiveMaxLevel}, #{generation}, #{createdAt}, #{createdBy}, #{updatedAt}, #{updatedBy}, #{deleted}, #{version}) on conflict (tenant_id, conversation_session_id) do nothing";

    /** 幂等创建会话的第零代空 manifest。 */
    @InterceptorIgnore(tenantLine = TENANT_LINE_IGNORED)
    // PostgreSQL ON CONFLICT 保证唯一键竞争不污染调用方事务，通用 insert 无此语义。
    @Insert(INSERT_IGNORE)
    int insertIgnore(LsmContextManifestEntity manifest);
}
