package com.datagent.agentv2.context.lsm.persistence.repository;

import com.datagent.agentv2.context.lsm.persistence.entity.LsmCompactionJobEntity;
import com.datagent.agentv2.context.lsm.persistence.mapper.LsmCompactionJobMapper;
import com.datagent.agentv2.platform.context.TenantContext;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 封装可恢复 LSM 压缩作业的插件私有查询。 */
@Repository
public final class LsmCompactionJobRepository {

    private final LsmCompactionJobMapper mapper;

    public LsmCompactionJobRepository(LsmCompactionJobMapper mapper) {
        this.mapper = mapper;
    }

    /** 读取当前租户 execution 边界上仍可恢复的作业。 */
    public Optional<LsmCompactionJobEntity> findRecoverableJob(
            String executionId, long executionAttempt, long inputBoundary) {
        return Optional.ofNullable(
                mapper.selectRecoverableJob(
                        TenantContext.requiredTenantId(),
                        executionId,
                        executionAttempt,
                        inputBoundary));
    }
}
