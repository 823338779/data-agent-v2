package com.datagent.agentv2.context.hierarchical.persistence.repository;

import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.persistence.entity.HierarchicalCheckpointEntity;
import com.datagent.agentv2.context.hierarchical.persistence.json.HierarchicalDocumentCodec;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.HierarchicalCheckpointMapper;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.LocalDateTime;
import org.springframework.stereotype.Repository;

/** 封装每个会话唯一 checkpoint 的创建、读取和 fenced CAS 发布。 */
@Repository
public class HierarchicalCheckpointRepository {

    private static final int FRONTIER_SCHEMA_VERSION = 1;
    private static final String SYSTEM_OPERATOR = "hierarchical-context";
    private static final String EXECUTION_TENANT_MISMATCH =
            "Hierarchical execution tenant does not match context";
    private static final String CHECKPOINT_SESSION_MISMATCH =
            "Hierarchical checkpoint session does not match execution";
    private static final String CHECKPOINT_NOT_PERSISTED =
            "Hierarchical checkpoint was not persisted";

    /** checkpoint SQL 与 JSON 文档门禁依赖。 */
    private final HierarchicalCheckpointMapper mapper;

    private final HierarchicalDocumentCodec documents;

    public HierarchicalCheckpointRepository(
            HierarchicalCheckpointMapper mapper, HierarchicalDocumentCodec documents) {
        this.mapper = mapper;
        this.documents = documents;
    }

    /** 读取当前租户会话的唯一有效 checkpoint，缺失时返回 null。 */
    public HierarchicalCheckpoint findCheckpoint(String conversationSessionId) {
        HierarchicalCheckpointEntity entity =
                mapper.selectCheckpoint(TenantContext.requiredTenantId(), conversationSessionId);
        return entity == null ? null : toDomain(entity);
    }

    /** 幂等创建初始 checkpoint，并返回唯一键竞争后的持久化记录。 */
    public HierarchicalCheckpoint create(HierarchicalCheckpoint candidate) {
        String tenantId = TenantContext.requiredTenantId();
        HierarchicalCheckpointEntity entity = toEntity(candidate, tenantId, true);
        mapper.insertIgnore(entity);
        HierarchicalCheckpoint persisted = findCheckpoint(candidate.conversationSessionId());
        if (persisted == null) {
            throw new IllegalStateException(CHECKPOINT_NOT_PERSISTED);
        }
        return persisted;
    }

    /** 仅在 version、generation、单调指针及 execution job fence 全部匹配时发布。 */
    public boolean compareAndSetCheckpoint(
            HierarchicalCheckpoint expected,
            HierarchicalCheckpoint replacement,
            ContextExecutionRef execution) {
        String tenantId = TenantContext.requiredTenantId();
        requireExecutionBoundary(tenantId, expected, replacement, execution);
        HierarchicalCheckpointEntity expectedEntity = toEntity(expected, tenantId, false);
        HierarchicalCheckpointEntity replacementEntity = toEntity(replacement, tenantId, false);
        return mapper.compareAndSet(tenantId, expectedEntity, replacementEntity, execution) == 1;
    }

    /** 在 Mapper 调用前绑定租户和审计字段，并校验 versioned JSON。 */
    private HierarchicalCheckpointEntity toEntity(
            HierarchicalCheckpoint checkpoint, String tenantId, boolean creating) {
        LocalDateTime now = LocalDateTime.now();
        HierarchicalCheckpointEntity entity = new HierarchicalCheckpointEntity();
        entity.setHierarchicalCheckpointId(checkpoint.checkpointId());
        entity.setTenantId(tenantId);
        entity.setConversationSessionId(checkpoint.conversationSessionId());
        entity.setColdSummaryDocument(
                documents.canonicalizeVersionedDocument(
                        checkpoint.coldSummaryDocument(), checkpoint.summarySchemaVersion()));
        entity.setSummarizedUpToConversationMessageSequence(
                checkpoint.summarizedUpToConversationMessageSequence());
        entity.setSummarizedCompletedTurnCount(checkpoint.summarizedCompletedTurnCount());
        entity.setEstimatedTokenCount(checkpoint.estimatedTokenCount());
        entity.setSummarySchemaVersion(checkpoint.summarySchemaVersion());
        entity.setSummaryPromptVersion(checkpoint.summaryPromptVersion());
        entity.setModelProvider(checkpoint.modelProvider());
        entity.setModelName(checkpoint.modelName());
        entity.setActiveFrontierDocument(
                documents.canonicalizeVersionedDocument(
                        checkpoint.activeFrontierDocument(), FRONTIER_SCHEMA_VERSION));
        entity.setStrategyGeneration(checkpoint.strategyGeneration());
        entity.setCreatedAt(now);
        entity.setCreatedBy(SYSTEM_OPERATOR);
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(SYSTEM_OPERATOR);
        entity.setDeleted(false);
        entity.setVersion(checkpoint.version());
        if (!creating) {
            entity.setCreatedAt(null);
            entity.setCreatedBy(null);
        }
        return entity;
    }

    /** 将持久化记录恢复为不携带租户和审计细节的领域 checkpoint。 */
    private static HierarchicalCheckpoint toDomain(HierarchicalCheckpointEntity entity) {
        return new HierarchicalCheckpoint(
                entity.getHierarchicalCheckpointId(),
                entity.getConversationSessionId(),
                entity.getColdSummaryDocument(),
                entity.getSummarizedUpToConversationMessageSequence(),
                entity.getSummarizedCompletedTurnCount(),
                entity.getEstimatedTokenCount(),
                entity.getSummarySchemaVersion(),
                entity.getSummaryPromptVersion(),
                entity.getModelProvider(),
                entity.getModelName(),
                entity.getActiveFrontierDocument(),
                entity.getStrategyGeneration(),
                entity.getVersion());
    }

    /** 防止跨租户、跨会话的 replacement 参与 checkpoint CAS。 */
    private static void requireExecutionBoundary(
            String tenantId,
            HierarchicalCheckpoint expected,
            HierarchicalCheckpoint replacement,
            ContextExecutionRef execution) {
        if (!tenantId.equals(execution.tenantId())) {
            throw new IllegalArgumentException(EXECUTION_TENANT_MISMATCH);
        }
        if (!expected.conversationSessionId().equals(execution.conversationSessionId())
                || !replacement.conversationSessionId().equals(execution.conversationSessionId())) {
            throw new IllegalArgumentException(CHECKPOINT_SESSION_MISMATCH);
        }
    }
}
