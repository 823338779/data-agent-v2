package com.datagent.agentv2.context.lsm.persistence.repository;

import com.datagent.agentv2.context.lsm.persistence.entity.LsmContextManifestEntity;
import com.datagent.agentv2.context.lsm.persistence.mapper.LsmContextManifestMapper;
import com.datagent.agentv2.platform.context.TenantContext;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 封装每个会话唯一 manifest 的读取和版本加代次 CAS 发布。 */
@Repository
@RequiredArgsConstructor
public class LsmContextManifestRepository {

    private final LsmContextManifestMapper mapper;

    /** 读取当前租户会话的唯一 manifest。 */
    public Optional<LsmContextManifestEntity> findManifest(String sessionId) {
        return Optional.ofNullable(
                mapper.selectManifest(TenantContext.requiredTenantId(), sessionId));
    }

    /** 幂等创建第零代空视图，并返回并发竞争后的唯一持久化记录。 */
    public LsmContextManifestEntity findOrCreate(LsmContextManifestEntity candidate) {
        String tenantId = TenantContext.requiredTenantId();
        candidate.setTenantId(tenantId);
        mapper.insertIgnore(candidate);
        return findManifest(candidate.getConversationSessionId())
                .orElseThrow(() -> new IllegalStateException("LSM manifest was not persisted"));
    }

    /** 仅当预期版本和 generation 同时匹配时发布 replacement。 */
    public boolean compareAndSetManifest(
            String sessionId,
            long expectedVersion,
            long expectedGeneration,
            String jobId,
            String ownerInstanceId,
            long executionAttempt,
            long inputBoundary,
            LsmContextManifestEntity replacement) {
        return mapper.compareAndSet(
                        TenantContext.requiredTenantId(),
                        sessionId,
                        expectedVersion,
                        expectedGeneration,
                        jobId,
                        ownerInstanceId,
                        executionAttempt,
                        inputBoundary,
                        replacement)
                == 1;
    }

    /** 兼容不带 job fencing 的底层 repository 测试；运行时发布必须使用完整重载。 */
    public boolean compareAndSetManifest(
            String sessionId,
            long expectedVersion,
            long expectedGeneration,
            LsmContextManifestEntity replacement) {
        return mapper.compareAndSetUnfenced(
                        TenantContext.requiredTenantId(),
                        sessionId,
                        expectedVersion,
                        expectedGeneration,
                        replacement)
                == 1;
    }
}
