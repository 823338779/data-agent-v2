package com.datagent.agentv2.context.lsm.persistence.repository;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.context.lsm.persistence.entity.LsmSummarySegmentEntity;
import com.datagent.agentv2.context.lsm.persistence.mapper.LsmSummarySegmentMapper;
import com.datagent.agentv2.platform.context.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** 封装不可变摘要段的插件私有 Mapper 访问和租户边界。 */
@Repository
@ConditionalOnProperty(prefix = "data-agent.context", name = "strategy", havingValue = "lsm")
public class LsmSummarySegmentRepository
        extends ServiceImpl<LsmSummarySegmentMapper, LsmSummarySegmentEntity> {

    private static final String ERROR_MANIFEST_INCONSISTENT =
            "LSM manifest active segment set is inconsistent";

    /** 按当前租户的会话和确定性段键读取摘要段。 */
    public Optional<LsmSummarySegmentEntity> findBySegmentKey(String sessionId, String segmentKey) {
        return Optional.ofNullable(querySegment(sessionId, segmentKey));
    }

    /** 按调用方指定的段标识顺序返回当前租户的未删除摘要段。 */
    public List<LsmSummarySegmentEntity> findByIdsInOrder(List<String> segmentIds) {
        if (segmentIds.isEmpty()) {
            return List.of();
        }
        Map<String, LsmSummarySegmentEntity> byId = new LinkedHashMap<>();
        for (LsmSummarySegmentEntity segment :
                lambdaQuery()
                        .eq(LsmSummarySegmentEntity::getTenantId, TenantContext.requiredTenantId())
                        .eq(LsmSummarySegmentEntity::isDeleted, false)
                        .in(LsmSummarySegmentEntity::getSummarySegmentId, segmentIds)
                        .list()) {
            byId.put(segment.getSummarySegmentId(), segment);
        }
        if (byId.size() != segmentIds.size()
                || segmentIds.stream().distinct().count() != segmentIds.size()) {
            throw new IllegalStateException(ERROR_MANIFEST_INCONSISTENT);
        }
        List<LsmSummarySegmentEntity> ordered = segmentIds.stream().map(byId::get).toList();
        if (ordered.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalStateException(ERROR_MANIFEST_INCONSISTENT);
        }
        return ordered;
    }

    /** 按覆盖顺序读取会话全部不可变段。 */
    public List<LsmSummarySegmentEntity> findBySession(String sessionId) {
        return lambdaQuery()
                .eq(LsmSummarySegmentEntity::getTenantId, TenantContext.requiredTenantId())
                .eq(LsmSummarySegmentEntity::getConversationSessionId, sessionId)
                .eq(LsmSummarySegmentEntity::isDeleted, false)
                .orderByAsc(
                        LsmSummarySegmentEntity::getStartTurnSequence,
                        LsmSummarySegmentEntity::getEndTurnSequence,
                        LsmSummarySegmentEntity::getLevel,
                        LsmSummarySegmentEntity::getSummarySegmentId)
                .list();
    }

    /** 写入不存在的确定性摘要段，重复提交返回首个已持久化段。 */
    public LsmSummarySegmentEntity findOrCreate(LsmSummarySegmentEntity candidate) {
        String tenantId = TenantContext.requiredTenantId();
        LsmSummarySegmentEntity existing =
                querySegment(candidate.getConversationSessionId(), candidate.getSegmentKey());
        if (existing != null) {
            return existing;
        }
        candidate.setTenantId(tenantId);
        baseMapper.insertIgnore(candidate);
        LsmSummarySegmentEntity persisted =
                querySegment(candidate.getConversationSessionId(), candidate.getSegmentKey());
        if (persisted == null) {
            throw new IllegalStateException("LSM summary segment was not persisted");
        }
        return persisted;
    }

    private LsmSummarySegmentEntity querySegment(String sessionId, String segmentKey) {
        return lambdaQuery()
                .eq(LsmSummarySegmentEntity::getTenantId, TenantContext.requiredTenantId())
                .eq(LsmSummarySegmentEntity::getConversationSessionId, sessionId)
                .eq(LsmSummarySegmentEntity::getSegmentKey, segmentKey)
                .eq(LsmSummarySegmentEntity::isDeleted, false)
                .one();
    }
}
