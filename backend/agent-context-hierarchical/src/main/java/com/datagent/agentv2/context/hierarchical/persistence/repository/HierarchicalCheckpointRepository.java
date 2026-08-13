package com.datagent.agentv2.context.hierarchical.persistence.repository;

import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.persistence.entity.HierarchicalCheckpointEntity;
import com.datagent.agentv2.context.hierarchical.persistence.json.HierarchicalDocumentCodec;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.HierarchicalCheckpointMapper;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextSummaryModelIdentity;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.LocalDateTime;

/** 封装每个会话唯一 checkpoint 的创建、读取和 fenced CAS 发布。 */
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

    /**
     * Rebuilds an incompatible published tree from immutable raw history under the new identity.
     */
    public HierarchicalCheckpoint migrateIdentityIfRequired(
            ContextExecutionRef execution,
            HierarchicalContextPolicySnapshot policy,
            ContextSummaryModelIdentity model) {
        String tenantId = TenantContext.requiredTenantId();
        if (!tenantId.equals(execution.tenantId())) {
            throw new IllegalArgumentException(EXECUTION_TENANT_MISMATCH);
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            HierarchicalCheckpoint current = findCheckpoint(execution.conversationSessionId());
            if (current == null) {
                throw new IllegalStateException(CHECKPOINT_NOT_PERSISTED);
            }
            boolean compatible =
                    current.summarySchemaVersion() == policy.summarySchemaVersion()
                            && current.summaryPromptVersion() == policy.summaryPromptVersion()
                            && current.modelProvider().equals(model.provider())
                            && current.modelName().equals(model.model());
            if (compatible) {
                return current;
            }
            mapper.migrateIdentity(
                    tenantId,
                    execution,
                    current.version(),
                    current.strategyGeneration(),
                    policy.summarySchemaVersion(),
                    policy.summaryPromptVersion(),
                    model.provider(),
                    model.model());
        }
        HierarchicalCheckpoint migrated = findCheckpoint(execution.conversationSessionId());
        if (migrated == null
                || migrated.summarySchemaVersion() != policy.summarySchemaVersion()
                || migrated.summaryPromptVersion() != policy.summaryPromptVersion()
                || !migrated.modelProvider().equals(model.provider())
                || !migrated.modelName().equals(model.model())) {
            throw new IllegalStateException("Hierarchical checkpoint identity migration failed");
        }
        return migrated;
    }

    /** 仅在 version、generation、单调指针及 execution job fence 全部匹配时发布。 */
    public boolean compareAndSetCheckpoint(
            HierarchicalCheckpoint expected,
            HierarchicalCheckpoint replacement,
            ContextExecutionRef execution) {
        return compareAndSetCheckpoint(expected, replacement, null, execution);
    }

    /** 运行时发布必须把 READY job 业务 ID 加入 SQL fence，不能借用其他作业授权。 */
    public boolean compareAndSetCheckpoint(
            HierarchicalCheckpoint expected,
            HierarchicalCheckpoint replacement,
            String jobId,
            ContextExecutionRef execution) {
        String tenantId = TenantContext.requiredTenantId();
        requireExecutionBoundary(tenantId, expected, replacement, execution);
        if (!isNextReplacement(expected, replacement)) {
            return false;
        }
        HierarchicalCheckpointEntity expectedEntity = toEntity(expected, tenantId, false);
        HierarchicalCheckpointEntity replacementEntity = toEntity(replacement, tenantId, false);
        String fencedJobId = jobId == null ? findReadyJobId(execution, expected) : jobId;
        return fencedJobId != null
                && mapper.compareAndSet(
                                tenantId, expectedEntity, replacementEntity, fencedJobId, execution)
                        == 1;
    }

    /** 兼容既有仓储测试；运行时始终显式传入 jobId。 */
    private String findReadyJobId(ContextExecutionRef execution, HierarchicalCheckpoint expected) {
        return mapper.selectReadyJobId(
                TenantContext.requiredTenantId(),
                execution,
                expected.version(),
                expected.strategyGeneration());
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

    private static boolean isNextReplacement(
            HierarchicalCheckpoint expected, HierarchicalCheckpoint replacement) {
        return expected.checkpointId().equals(replacement.checkpointId())
                && replacement.strategyGeneration() == expected.strategyGeneration() + 1L;
    }
}
