package com.datagent.agentv2.context.hierarchical.persistence.repository;

import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.persistence.entity.HierarchicalCheckpointEntity;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.HierarchicalCheckpointMapper;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.LocalDateTime;
import java.util.Objects;

/** 封装会话唯一 checkpoint 的幂等创建、读取和 fenced CAS。 */
public final class HierarchicalCheckpointRepository {
    private static final String SYSTEM_OPERATOR = "hierarchical-context";
    private final HierarchicalCheckpointMapper mapper;

    public HierarchicalCheckpointRepository(HierarchicalCheckpointMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public HierarchicalCheckpoint findCheckpoint(String sessionId) {
        HierarchicalCheckpointEntity entity =
                mapper.selectCheckpoint(TenantContext.requiredTenantId(), sessionId);
        return entity == null ? null : toDomain(entity);
    }

    public HierarchicalCheckpoint create(HierarchicalCheckpoint candidate) {
        String tenantId = TenantContext.requiredTenantId();
        mapper.insertIgnore(toEntity(candidate, tenantId, true));
        HierarchicalCheckpoint persisted = findCheckpoint(candidate.conversationSessionId());
        if (persisted == null)
            throw new IllegalStateException("Hierarchical checkpoint was not persisted");
        return persisted;
    }

    public boolean compareAndSetCheckpoint(
            HierarchicalCheckpoint expected, HierarchicalCheckpoint replacement) {
        String tenantId = TenantContext.requiredTenantId();
        if (!expected.conversationSessionId().equals(replacement.conversationSessionId())) {
            throw new IllegalArgumentException(
                    "Hierarchical checkpoint execution boundary mismatch");
        }
        if (!expected.checkpointId().equals(replacement.checkpointId())
                || replacement.strategyGeneration() != expected.strategyGeneration() + 1L)
            return false;
        return mapper.compareAndSet(
                        tenantId,
                        toEntity(expected, tenantId, false),
                        toEntity(replacement, tenantId, false))
                == 1;
    }

    private static HierarchicalCheckpointEntity toEntity(
            HierarchicalCheckpoint checkpoint, String tenantId, boolean creating) {
        HierarchicalCheckpointEntity entity = new HierarchicalCheckpointEntity();
        entity.setHierarchicalCheckpointId(checkpoint.checkpointId());
        entity.setTenantId(tenantId);
        entity.setConversationSessionId(checkpoint.conversationSessionId());
        entity.setActiveColdId(checkpoint.activeColdId());
        entity.setEvaluatedUpToConversationMessageSequence(
                checkpoint.evaluatedUpToConversationMessageSequence());
        entity.setStrategyGeneration(checkpoint.strategyGeneration());
        entity.setVersion(checkpoint.version());
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(creating ? now : null);
        entity.setCreatedBy(creating ? SYSTEM_OPERATOR : null);
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(SYSTEM_OPERATOR);
        entity.setDeleted(false);
        return entity;
    }

    private static HierarchicalCheckpoint toDomain(HierarchicalCheckpointEntity entity) {
        return new HierarchicalCheckpoint(
                entity.getHierarchicalCheckpointId(),
                entity.getConversationSessionId(),
                entity.getActiveColdId(),
                entity.getEvaluatedUpToConversationMessageSequence(),
                entity.getStrategyGeneration(),
                entity.getVersion());
    }
}
