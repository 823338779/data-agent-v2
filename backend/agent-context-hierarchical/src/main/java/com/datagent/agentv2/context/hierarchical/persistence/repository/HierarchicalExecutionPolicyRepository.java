package com.datagent.agentv2.context.hierarchical.persistence.repository;

import com.datagent.agentv2.context.hierarchical.domain.HierarchicalContextPolicySnapshot;
import com.datagent.agentv2.context.hierarchical.persistence.entity.HierarchicalExecutionPolicyEntity;
import com.datagent.agentv2.context.hierarchical.persistence.json.HierarchicalDocumentCodec;
import com.datagent.agentv2.context.hierarchical.persistence.mapper.HierarchicalExecutionPolicyMapper;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.platform.context.TenantContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.LocalDateTime;
import org.springframework.stereotype.Repository;

/** 封装 execution 策略快照的一次创建、多次读取语义。 */
@Repository
public class HierarchicalExecutionPolicyRepository {

    private static final String ID_PREFIX = "hierarchical_execution_policy";
    private static final String SYSTEM_OPERATOR = "hierarchical-context";
    private static final String EXECUTION_TENANT_MISMATCH =
            "Hierarchical execution tenant does not match context";
    private static final String POLICY_NOT_PERSISTED =
            "Hierarchical execution policy was not persisted";

    /** 持久化读写和文档编解码依赖。 */
    private final HierarchicalExecutionPolicyMapper mapper;

    private final HierarchicalDocumentCodec documents;
    private final IdGenerator ids;

    public HierarchicalExecutionPolicyRepository(
            HierarchicalExecutionPolicyMapper mapper,
            HierarchicalDocumentCodec documents,
            IdGenerator ids) {
        this.mapper = mapper;
        this.documents = documents;
        this.ids = ids;
    }

    /** 返回首次冻结策略；唯一键竞争时读取首个成功写入的快照。 */
    public HierarchicalContextPolicySnapshot findOrCreate(
            ContextExecutionRef execution, HierarchicalContextPolicySnapshot configured) {
        String tenantId = TenantContext.requiredTenantId();
        requireExecutionTenant(tenantId, execution);
        HierarchicalExecutionPolicyEntity existing =
                mapper.selectPolicy(tenantId, execution.executionId());
        if (existing != null) {
            return documents.readPolicy(existing.getPolicyDocument());
        }

        LocalDateTime now = LocalDateTime.now();
        HierarchicalExecutionPolicyEntity candidate = new HierarchicalExecutionPolicyEntity();
        candidate.setHierarchicalExecutionPolicyId(ids.next(ID_PREFIX));
        candidate.setTenantId(tenantId);
        candidate.setConversationSessionId(execution.conversationSessionId());
        candidate.setExecutionId(execution.executionId());
        candidate.setPolicyDocument(documents.writePolicy(configured));
        candidate.setCreatedAt(now);
        candidate.setCreatedBy(SYSTEM_OPERATOR);
        candidate.setUpdatedAt(now);
        candidate.setUpdatedBy(SYSTEM_OPERATOR);
        candidate.setDeleted(false);
        candidate.setVersion(0L);
        mapper.insertIgnore(candidate);

        HierarchicalExecutionPolicyEntity persisted =
                mapper.selectPolicy(tenantId, execution.executionId());
        if (persisted == null) {
            throw new IllegalStateException(POLICY_NOT_PERSISTED);
        }
        return documents.readPolicy(persisted.getPolicyDocument());
    }

    /** 防止调用方把其他租户的 execution 标识写入当前租户。 */
    private static void requireExecutionTenant(String tenantId, ContextExecutionRef execution) {
        if (!tenantId.equals(execution.tenantId())) {
            throw new IllegalArgumentException(EXECUTION_TENANT_MISMATCH);
        }
    }
}
