package com.datagent.agentv2.context.lsm.persistence.repository;

import com.datagent.agentv2.context.lsm.persistence.entity.LsmExecutionPolicyEntity;
import com.datagent.agentv2.context.lsm.persistence.json.LsmDocumentCodec;
import com.datagent.agentv2.context.lsm.persistence.mapper.LsmExecutionPolicyMapper;
import com.datagent.agentv2.conversation.context.ContextExecutionRef;
import com.datagent.agentv2.conversation.context.ContextPolicySnapshot;
import com.datagent.agentv2.platform.context.TenantContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import com.datagent.agentv2.platform.id.PrefixedUuidIdGenerator;
import java.time.LocalDateTime;
import org.springframework.stereotype.Repository;

/** 封装执行策略快照的一次创建、多次读取语义。 */
@Repository
public final class LsmExecutionPolicyRepository {

    private static final String ID_PREFIX_EXECUTION_POLICY = "context_execution_policy";
    private static final String SYSTEM_OPERATOR = "lsm-context";
    private static final String EXECUTION_TENANT_MISMATCH =
            "LSM execution tenant does not match context";
    private static final String POLICY_NOT_PERSISTED = "LSM execution policy was not persisted";

    private final LsmExecutionPolicyMapper mapper;
    private final LsmDocumentCodec documents;
    private final IdGenerator ids;

    public LsmExecutionPolicyRepository(
            LsmExecutionPolicyMapper mapper, LsmDocumentCodec documents, IdGenerator ids) {
        this.mapper = mapper;
        this.documents = documents;
        this.ids = ids;
    }

    /** 为轻量 Mapper 测试提供与运行时一致的默认 JSON 和 ID 依赖。 */
    public LsmExecutionPolicyRepository(LsmExecutionPolicyMapper mapper) {
        this(mapper, new LsmDocumentCodec(), new PrefixedUuidIdGenerator());
    }

    /** 返回已冻结策略；缺失时以唯一键幂等写入本次 execution 的配置快照。 */
    public ContextPolicySnapshot findOrCreateExecutionPolicy(
            ContextExecutionRef execution, ContextPolicySnapshot configuredPolicy) {
        String tenantId = TenantContext.requiredTenantId();
        if (!tenantId.equals(execution.tenantId())) {
            throw new IllegalArgumentException(EXECUTION_TENANT_MISMATCH);
        }
        LsmExecutionPolicyEntity existing = mapper.selectPolicy(tenantId, execution.executionId());
        if (existing != null) {
            return documents.readPolicy(existing.getPolicySnapshot());
        }

        LocalDateTime now = LocalDateTime.now();
        LsmExecutionPolicyEntity candidate = new LsmExecutionPolicyEntity();
        candidate.setExecutionPolicyId(ids.next(ID_PREFIX_EXECUTION_POLICY));
        candidate.setTenantId(tenantId);
        candidate.setConversationSessionId(execution.conversationSessionId());
        candidate.setExecutionId(execution.executionId());
        candidate.setPolicySnapshot(documents.writePolicy(configuredPolicy));
        candidate.setCreatedAt(now);
        candidate.setCreatedBy(SYSTEM_OPERATOR);
        candidate.setUpdatedAt(now);
        candidate.setUpdatedBy(SYSTEM_OPERATOR);
        candidate.setDeleted(false);
        candidate.setVersion(0L);
        mapper.insertIgnore(candidate);

        LsmExecutionPolicyEntity persisted = mapper.selectPolicy(tenantId, execution.executionId());
        if (persisted == null) {
            throw new IllegalStateException(POLICY_NOT_PERSISTED);
        }
        return documents.readPolicy(persisted.getPolicySnapshot());
    }
}
