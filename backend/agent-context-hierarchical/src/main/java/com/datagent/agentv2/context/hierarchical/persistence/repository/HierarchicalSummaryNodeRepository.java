package com.datagent.agentv2.context.hierarchical.persistence.repository;

import com.datagent.agentv2.context.hierarchical.domain.HierarchicalSummaryNode;
import com.datagent.agentv2.context.hierarchical.persistence.entity.HierarchicalSummaryNodeEntity;
import com.datagent.agentv2.context.hierarchical.persistence.json.HierarchicalDocumentCodec;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.HierarchicalSummaryNodeMapper;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.LocalDateTime;
import org.springframework.stereotype.Repository;

/** 封装不可变摘要节点的租户隔离和 identity 幂等写入。 */
@Repository
public class HierarchicalSummaryNodeRepository {

    private static final String SYSTEM_OPERATOR = "hierarchical-context";
    private static final String NODE_NOT_PERSISTED = "Hierarchical summary node was not persisted";

    /** 摘要节点 SQL 与 JSON 文档门禁依赖。 */
    private final HierarchicalSummaryNodeMapper mapper;

    private final HierarchicalDocumentCodec documents;

    public HierarchicalSummaryNodeRepository(
            HierarchicalSummaryNodeMapper mapper, HierarchicalDocumentCodec documents) {
        this.mapper = mapper;
        this.documents = documents;
    }

    /** 同 identity 重复提交时返回首次持久化的不可变节点。 */
    public HierarchicalSummaryNode insertOrSelect(HierarchicalSummaryNode candidate) {
        String tenantId = TenantContext.requiredTenantId();
        HierarchicalSummaryNodeEntity entity = toEntity(candidate, tenantId);
        HierarchicalSummaryNodeEntity existing = mapper.selectIdentity(tenantId, entity);
        if (existing != null) {
            return toDomain(existing);
        }
        mapper.insertIgnore(entity);
        HierarchicalSummaryNodeEntity persisted = mapper.selectIdentity(tenantId, entity);
        if (persisted == null) {
            throw new IllegalStateException(NODE_NOT_PERSISTED);
        }
        return toDomain(persisted);
    }

    /** 绑定当前租户、审计字段并拒绝摘要 schema 版本不匹配。 */
    private HierarchicalSummaryNodeEntity toEntity(HierarchicalSummaryNode node, String tenantId) {
        LocalDateTime now = LocalDateTime.now();
        HierarchicalSummaryNodeEntity entity = new HierarchicalSummaryNodeEntity();
        entity.setHierarchicalSummaryNodeId(node.nodeId());
        entity.setTenantId(tenantId);
        entity.setConversationSessionId(node.conversationSessionId());
        entity.setLevel(node.level());
        entity.setCoveredCompletedTurnCount(node.coveredCompletedTurnCount());
        entity.setStartConversationMessageSequence(node.startConversationMessageSequence());
        entity.setEndConversationMessageSequence(node.endConversationMessageSequence());
        entity.setLeftChildNodeId(node.leftChildNodeId());
        entity.setRightChildNodeId(node.rightChildNodeId());
        entity.setSummaryDocument(
                documents.canonicalizeVersionedDocument(
                        node.summaryDocument(), node.summarySchemaVersion()));
        entity.setEstimatedTokenCount(node.estimatedTokenCount());
        entity.setSummarySchemaVersion(node.summarySchemaVersion());
        entity.setSummaryPromptVersion(node.summaryPromptVersion());
        entity.setModelProvider(node.modelProvider());
        entity.setModelName(node.modelName());
        entity.setCreatedAt(now);
        entity.setCreatedBy(SYSTEM_OPERATOR);
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(SYSTEM_OPERATOR);
        entity.setDeleted(false);
        entity.setVersion(0L);
        return entity;
    }

    /** 恢复不暴露租户、审计和持久化版本的不可变领域节点。 */
    private static HierarchicalSummaryNode toDomain(HierarchicalSummaryNodeEntity entity) {
        return new HierarchicalSummaryNode(
                entity.getHierarchicalSummaryNodeId(),
                entity.getConversationSessionId(),
                entity.getLevel(),
                entity.getCoveredCompletedTurnCount(),
                entity.getStartConversationMessageSequence(),
                entity.getEndConversationMessageSequence(),
                entity.getLeftChildNodeId(),
                entity.getRightChildNodeId(),
                entity.getSummaryDocument(),
                entity.getEstimatedTokenCount(),
                entity.getSummarySchemaVersion(),
                entity.getSummaryPromptVersion(),
                entity.getModelProvider(),
                entity.getModelName());
    }
}
