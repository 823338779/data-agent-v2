package com.datagent.agentv2.context.hierarchical.persistence.repository;

import com.datagent.agentv2.context.hierarchical.domain.HierarchicalSummaryNode;
import com.datagent.agentv2.context.hierarchical.persistence.entity.HierarchicalSummaryNodeEntity;
import com.datagent.agentv2.context.hierarchical.persistence.json.HierarchicalDocumentCodec;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.HierarchicalSummaryNodeMapper;
import com.datagent.agentv2.context.hierarchical.persistence.mapping.HierarchicalPersistenceStructMapper;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;

/** 封装不可变摘要节点的租户隔离和 identity 幂等写入。 */
@RequiredArgsConstructor
public class HierarchicalSummaryNodeRepository {

    private static final String SYSTEM_OPERATOR = "hierarchical-context";
    private static final String NODE_NOT_PERSISTED = "Hierarchical summary node was not persisted";
    private static final String NODE_WRITE_FENCE_LOST =
            "Hierarchical summary node write lost its job fence";
    private static final String EXECUTION_TENANT_MISMATCH =
            "Hierarchical execution tenant does not match context";

    /** 摘要节点 SQL 与 JSON 文档门禁依赖。 */
    private final HierarchicalSummaryNodeMapper mapper;

    private final HierarchicalDocumentCodec documents;

    /** 同 identity 重复提交时返回首次持久化的不可变节点。 */
    public HierarchicalSummaryNode insertOrSelect(HierarchicalSummaryNode candidate) {
        String tenantId = TenantContext.requiredTenantId();
        HierarchicalSummaryNodeEntity entity = toEntity(candidate, tenantId);
        HierarchicalSummaryNodeEntity existing = mapper.selectIdentity(tenantId, entity);
        if (existing != null) {
            return HierarchicalPersistenceStructMapper.INSTANCE.toDomain(existing);
        }
        mapper.insertIgnore(entity);
        HierarchicalSummaryNodeEntity persisted = mapper.selectIdentity(tenantId, entity);
        if (persisted == null) {
            throw new IllegalStateException(NODE_NOT_PERSISTED);
        }
        return HierarchicalPersistenceStructMapper.INSTANCE.toDomain(persisted);
    }

    /** 首次生成结果只通过数据库内 exact job fence 写入；唯一键冲突或 takeover 均视为本次写入失败。 */
    public HierarchicalSummaryNode insertOrSelectFenced(
            String jobId, ContextExecutionRef execution, HierarchicalSummaryNode candidate) {
        String tenantId = TenantContext.requiredTenantId();
        ContextExecutionRef checkedExecution = Objects.requireNonNull(execution, "execution");
        if (!tenantId.equals(checkedExecution.tenantId())) {
            throw new IllegalArgumentException(EXECUTION_TENANT_MISMATCH);
        }
        HierarchicalSummaryNodeEntity entity = toEntity(candidate, tenantId);

        // 不先读 identity：generation 入口已记录可复用节点，本方法只代表一次首次写入尝试。
        if (mapper.insertFenced(tenantId, jobId, checkedExecution, entity) != 1) {
            throw new IllegalStateException(NODE_WRITE_FENCE_LOST);
        }
        HierarchicalSummaryNodeEntity persisted = mapper.selectIdentity(tenantId, entity);
        if (persisted == null) {
            throw new IllegalStateException(NODE_NOT_PERSISTED);
        }
        return HierarchicalPersistenceStructMapper.INSTANCE.toDomain(persisted);
    }

    /** 仅在当前租户和指定会话内读取 ID 集合；返回顺序不构成仓储契约。 */
    public List<HierarchicalSummaryNode> findByIds(
            String conversationSessionId, List<String> nodeIds) {
        String sessionId = Objects.requireNonNull(conversationSessionId, "conversationSessionId");
        List<String> ids = List.copyOf(Objects.requireNonNull(nodeIds, "nodeIds"));
        if (ids.isEmpty()) {
            return List.of();
        }

        // 查询同时绑定 required tenant 与 conversation session，避免仅凭全局 ID 越界读取。
        return mapper.selectNodesByIds(TenantContext.requiredTenantId(), sessionId, ids).stream()
                .map(HierarchicalPersistenceStructMapper.INSTANCE::toDomain)
                .toList();
    }

    /** 绑定当前租户、审计字段并拒绝摘要 schema 版本不匹配。 */
    private HierarchicalSummaryNodeEntity toEntity(HierarchicalSummaryNode node, String tenantId) {
        LocalDateTime now = LocalDateTime.now();
        HierarchicalSummaryNodeEntity entity =
                HierarchicalPersistenceStructMapper.INSTANCE.toEntity(node);
        entity.setTenantId(tenantId);
        entity.setSummaryDocument(
                documents.canonicalizeSummaryDocument(
                        node.summaryDocument(),
                        node.summarySchemaVersion(),
                        node.summaryPromptVersion()));
        entity.setCreatedAt(now);
        entity.setCreatedBy(SYSTEM_OPERATOR);
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(SYSTEM_OPERATOR);
        entity.setDeleted(false);
        entity.setVersion(0L);
        return entity;
    }
}
