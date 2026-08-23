package com.datagent.agentv2.context.hierarchical.persistence.repository;

import com.datagent.agentv2.context.common.summary.EventSummaryCoverage;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCold;
import com.datagent.agentv2.context.hierarchical.persistence.entity.HierarchicalColdEntity;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.HierarchicalColdMapper;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.LocalDateTime;
import java.util.Objects;

/** 封装滚动 Cold 的租户隔离读取和确定性 ID 幂等写入。 */
public final class HierarchicalColdRepository {
    private static final String SYSTEM_OPERATOR = "hierarchical-context";
    private final HierarchicalColdMapper mapper;

    public HierarchicalColdRepository(HierarchicalColdMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public HierarchicalCold insertOrSelect(HierarchicalCold cold) {
        String tenantId = TenantContext.requiredTenantId();
        HierarchicalColdEntity existing =
                mapper.selectCold(tenantId, cold.conversationSessionId(), cold.coldId());
        if (existing == null) {
            mapper.insertIgnore(toEntity(cold, tenantId));
            existing = mapper.selectCold(tenantId, cold.conversationSessionId(), cold.coldId());
        }
        if (existing == null) {
            throw new IllegalStateException("Hierarchical Cold was not persisted");
        }
        return toDomain(existing);
    }

    public HierarchicalCold find(String sessionId, String coldId) {
        HierarchicalColdEntity entity =
                mapper.selectCold(
                        TenantContext.requiredTenantId(),
                        Objects.requireNonNull(sessionId, "sessionId"),
                        Objects.requireNonNull(coldId, "coldId"));
        return entity == null ? null : toDomain(entity);
    }

    private static HierarchicalColdEntity toEntity(HierarchicalCold cold, String tenantId) {
        HierarchicalColdEntity entity = new HierarchicalColdEntity();
        entity.setHierarchicalColdId(cold.coldId());
        entity.setTenantId(tenantId);
        entity.setConversationSessionId(cold.conversationSessionId());
        entity.setStartTurnSequence(cold.coverage().startTurnSequence());
        entity.setEndTurnSequence(cold.coverage().endTurnSequence());
        entity.setStartMessageSequence(cold.coverage().startMessageSequence());
        entity.setEndMessageSequence(cold.coverage().endMessageSequence());
        entity.setSummaryDocument(cold.summaryDocument());
        entity.setPersistedTokenCount(cold.persistedTokenCount());
        entity.setRenderedTokenCount(cold.renderedTokenCount());
        entity.setTargetRenderedTokens(cold.targetRenderedTokens());
        entity.setSummarySchemaVersion(cold.summarySchemaVersion());
        entity.setSummaryPromptVersion(cold.summaryPromptVersion());
        entity.setModelProvider(cold.modelProvider());
        entity.setModelName(cold.modelName());
        entity.setSourceContentSha256(cold.sourceContentSha256());
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setCreatedBy(SYSTEM_OPERATOR);
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(SYSTEM_OPERATOR);
        entity.setDeleted(false);
        entity.setVersion(0L);
        return entity;
    }

    private static HierarchicalCold toDomain(HierarchicalColdEntity entity) {
        return new HierarchicalCold(
                entity.getHierarchicalColdId(),
                entity.getConversationSessionId(),
                new EventSummaryCoverage(
                        entity.getStartTurnSequence(),
                        entity.getEndTurnSequence(),
                        entity.getStartMessageSequence(),
                        entity.getEndMessageSequence()),
                entity.getSummaryDocument(),
                entity.getPersistedTokenCount(),
                entity.getRenderedTokenCount(),
                entity.getTargetRenderedTokens(),
                entity.getSummarySchemaVersion(),
                entity.getSummaryPromptVersion(),
                entity.getModelProvider(),
                entity.getModelName(),
                entity.getSourceContentSha256());
    }
}
