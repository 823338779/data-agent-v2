package com.datagent.agentv2.persistence.service.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.memory.MemoryReviewClaim;
import com.datagent.agentv2.memory.MemoryReviewLease;
import com.datagent.agentv2.persistence.entity.memory.MemoryReviewLeaseEntity;
import com.datagent.agentv2.persistence.mapper.memory.MemoryReviewLeaseMapper;
import com.datagent.agentv2.persistence.mapping.MemoryPersistenceMapper;
import com.datagent.agentv2.platform.context.OperatorContext;
import com.datagent.agentv2.platform.context.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 用户级审查租约锁与 CAS 原语；租约有效性始终按数据库时钟判断。 */
@Service
@RequiredArgsConstructor
public class MemoryReviewLeaseService
        extends ServiceImpl<MemoryReviewLeaseMapper, MemoryReviewLeaseEntity> {
    private final MemoryPersistenceMapper models;

    public MemoryReviewLease findCurrent() {
        return map(
                lambdaQuery()
                        .eq(MemoryReviewLeaseEntity::getUserId, OperatorContext.requiredUserId())
                        .one());
    }

    public MemoryReviewLease lockCurrent() {
        return map(
                lambdaQuery()
                        .eq(MemoryReviewLeaseEntity::getUserId, OperatorContext.requiredUserId())
                        .last("FOR UPDATE")
                        .one());
    }

    public MemoryReviewLease insert(MemoryReviewLease lease) {
        var entity = models.toEntity(lease);
        save(entity);
        return models.toModel(entity);
    }

    public boolean compareAndSet(MemoryReviewLease lease, long expectedToken) {
        // 显式 SET 可空列，保证释放租约不会被默认非空更新策略忽略。
        return baseMapper.update(
                        MemoryReviewLeaseEntity.create(),
                        Wrappers.lambdaUpdate(MemoryReviewLeaseEntity.class)
                                .eq(
                                        MemoryReviewLeaseEntity::getUserId,
                                        OperatorContext.requiredUserId())
                                .eq(MemoryReviewLeaseEntity::getFencingToken, expectedToken)
                                .set(MemoryReviewLeaseEntity::getFencingToken, lease.fencingToken())
                                .set(MemoryReviewLeaseEntity::getOwner, lease.owner())
                                .set(MemoryReviewLeaseEntity::getLeaseUntil, lease.leaseUntil()))
                == 1;
    }

    public boolean validateClaim(MemoryReviewClaim claim) {
        return baseMapper.validateLease(
                TenantContext.requiredTenantId(),
                OperatorContext.requiredUserId(),
                claim.userToken(),
                claim.owner());
    }

    private MemoryReviewLease map(MemoryReviewLeaseEntity entity) {
        return entity == null ? null : models.toModel(entity);
    }
}
