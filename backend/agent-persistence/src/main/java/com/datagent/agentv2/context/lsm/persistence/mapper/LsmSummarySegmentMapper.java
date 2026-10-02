package com.datagent.agentv2.context.lsm.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmSummarySegmentEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** 插件私有的摘要段 SQL 映射；所有调用必须经过持久化 repository。 */
@Mapper
public interface LsmSummarySegmentMapper extends BaseMapper<LsmSummarySegmentEntity> {

    String TENANT_LINE_IGNORED = "true";
    String INSERT_IGNORE =
            "insert into conversation_context_lsm_segment (summary_segment_id, tenant_id, conversation_session_id, segment_key, kind, level, start_turn_sequence, end_turn_sequence, start_message_sequence, end_message_sequence, partial_segment, summary_document, estimated_token_count, source_segment_ids, source_content_sha256, summary_schema_version, prompt_version, model_provider, model_name, creation_policy, source_segment_count, created_at, created_by, updated_at, updated_by, deleted, version) values (#{summarySegmentId}, #{tenantId}, #{conversationSessionId}, #{segmentKey}, #{kind}, #{level}, #{startTurnSequence}, #{endTurnSequence}, #{startMessageSequence}, #{endMessageSequence}, #{partialSegment}, #{summaryDocument, typeHandler=com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler, jdbcType=OTHER}, #{estimatedTokenCount}, #{sourceSegmentIds, typeHandler=com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler, jdbcType=OTHER}, #{sourceContentSha256}, #{summarySchemaVersion}, #{promptVersion}, #{modelProvider}, #{modelName}, #{creationPolicy, typeHandler=com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler, jdbcType=OTHER}, #{sourceSegmentCount}, #{createdAt}, #{createdBy}, #{updatedAt}, #{updatedBy}, #{deleted}, #{version}) on conflict (tenant_id, conversation_session_id, segment_key) do nothing";

    /** 利用唯一段键写入，重复规划时保留第一个成功的不可变段。 */
    @InterceptorIgnore(tenantLine = TENANT_LINE_IGNORED)
    // PostgreSQL ON CONFLICT 保证唯一键竞争不污染调用方事务，通用 insert 无此语义。
    @Insert(INSERT_IGNORE)
    int insertIgnore(LsmSummarySegmentEntity segment);
}
