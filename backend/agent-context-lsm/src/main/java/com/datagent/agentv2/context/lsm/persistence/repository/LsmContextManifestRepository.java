package com.datagent.agentv2.context.lsm.persistence.repository;

import com.datagent.agentv2.context.lsm.persistence.entity.LsmContextManifestEntity;
import com.datagent.agentv2.context.lsm.persistence.mapper.LsmContextManifestMapper;
import com.datagent.agentv2.platform.context.TenantContext;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 封装每个会话唯一 manifest 的读取和版本加代次 CAS 发布。 */
@Repository
public final class LsmContextManifestRepository {

    private final LsmContextManifestMapper mapper;

    public LsmContextManifestRepository(LsmContextManifestMapper mapper) {
        this.mapper = mapper;
    }

    /** 读取当前租户会话的唯一 manifest。 */
    public Optional<LsmContextManifestEntity> findManifest(String sessionId) {
        return Optional.ofNullable(
                mapper.selectManifest(TenantContext.requiredTenantId(), sessionId));
    }

    /** 仅当预期版本和 generation 同时匹配时发布 replacement。 */
    public boolean compareAndSetManifest(
            String sessionId,
            long expectedVersion,
            long expectedGeneration,
            LsmContextManifestEntity replacement) {
        return mapper.compareAndSet(
                        TenantContext.requiredTenantId(),
                        sessionId,
                        expectedVersion,
                        expectedGeneration,
                        replacement)
                == 1;
    }
}
