package com.datagent.agentv2.context.lsm.persistence.repository;

import com.datagent.agentv2.context.lsm.persistence.entity.LsmSummarySegmentEntity;
import com.datagent.agentv2.context.lsm.persistence.mapper.LsmSummarySegmentMapper;
import com.datagent.agentv2.platform.context.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 封装不可变摘要段的插件私有 Mapper 访问和租户边界。 */
@Repository
public final class LsmSummarySegmentRepository {

    private final LsmSummarySegmentMapper mapper;

    public LsmSummarySegmentRepository(LsmSummarySegmentMapper mapper) {
        this.mapper = mapper;
    }

    /** 按当前租户的会话和确定性段键读取摘要段。 */
    public Optional<LsmSummarySegmentEntity> findBySegmentKey(String sessionId, String segmentKey) {
        return Optional.ofNullable(
                mapper.selectBySegmentKey(TenantContext.requiredTenantId(), sessionId, segmentKey));
    }

    /** 按调用方指定的段标识顺序返回当前租户的未删除摘要段。 */
    public List<LsmSummarySegmentEntity> findByIdsInOrder(List<String> segmentIds) {
        if (segmentIds.isEmpty()) {
            return List.of();
        }
        Map<String, LsmSummarySegmentEntity> byId = new LinkedHashMap<>();
        for (LsmSummarySegmentEntity segment :
                mapper.selectByIds(TenantContext.requiredTenantId(), segmentIds)) {
            byId.put(segment.getSummarySegmentId(), segment);
        }
        return segmentIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    /** 写入不存在的确定性摘要段，重复提交返回首个已持久化段。 */
    public LsmSummarySegmentEntity findOrCreate(LsmSummarySegmentEntity candidate) {
        String tenantId = TenantContext.requiredTenantId();
        LsmSummarySegmentEntity existing =
                mapper.selectBySegmentKey(
                        tenantId, candidate.getConversationSessionId(), candidate.getSegmentKey());
        if (existing != null) {
            return existing;
        }
        candidate.setTenantId(tenantId);
        mapper.insertIgnore(candidate);
        LsmSummarySegmentEntity persisted =
                mapper.selectBySegmentKey(
                        tenantId, candidate.getConversationSessionId(), candidate.getSegmentKey());
        if (persisted == null) {
            throw new IllegalStateException("LSM summary segment was not persisted");
        }
        return persisted;
    }
}
