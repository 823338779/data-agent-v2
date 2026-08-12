package com.datagent.agentv2.context.lsm.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmContextManifestEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 插件私有的会话 manifest SQL 映射；CAS 保持一行会话视图的原子发布。 */
@Mapper
public interface LsmContextManifestMapper extends BaseMapper<LsmContextManifestEntity> {

    String TENANT_LINE_IGNORED = "true";
    String SELECT_MANIFEST =
            "select * from conversation_context_lsm_manifest where tenant_id = #{tenantId} and conversation_session_id = #{sessionId} and deleted = false";
    String COMPARE_AND_SET =
            "update conversation_context_lsm_manifest set active_root_segment_id = #{replacement.activeRootSegmentId}, active_segment_ids = #{replacement.activeSegmentIds, typeHandler=com.datagent.agentv2.persistence.typehandler.JsonbStringTypeHandler, jdbcType=OTHER}, pending_raw_start_turn_sequence = #{replacement.pendingRawStartTurnSequence}, pending_raw_end_turn_sequence = #{replacement.pendingRawEndTurnSequence}, warm_cutoff_turn_sequence = #{replacement.warmCutoffTurnSequence}, hot_from_turn_sequence = #{replacement.hotFromTurnSequence}, evaluated_up_to_message_sequence = #{replacement.evaluatedUpToMessageSequence}, effective_policy_version = #{replacement.effectivePolicyVersion}, effective_fanout = #{replacement.effectiveFanout}, effective_max_level = #{replacement.effectiveMaxLevel}, generation = #{replacement.generation}, updated_at = #{replacement.updatedAt}, updated_by = #{replacement.updatedBy}, version = version + 1 where tenant_id = #{tenantId} and conversation_session_id = #{sessionId} and version = #{expectedVersion} and generation = #{expectedGeneration} and deleted = false";

    /** 读取当前租户会话的未删除 manifest。 */
    @InterceptorIgnore(tenantLine = TENANT_LINE_IGNORED)
    @Select(SELECT_MANIFEST)
    LsmContextManifestEntity selectManifest(
            @Param("tenantId") String tenantId, @Param("sessionId") String sessionId);

    /** 同时比较乐观锁版本和业务代次后发布完整替换的 manifest。 */
    @InterceptorIgnore(tenantLine = TENANT_LINE_IGNORED)
    @Update(COMPARE_AND_SET)
    int compareAndSet(
            @Param("tenantId") String tenantId,
            @Param("sessionId") String sessionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("expectedGeneration") long expectedGeneration,
            @Param("replacement") LsmContextManifestEntity replacement);
}
