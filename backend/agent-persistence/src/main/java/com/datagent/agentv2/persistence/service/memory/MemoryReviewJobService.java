package com.datagent.agentv2.persistence.service.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.memory.MemoryReviewClaim;
import com.datagent.agentv2.memory.MemoryReviewJob;
import com.datagent.agentv2.persistence.entity.memory.MemoryReviewJobEntity;
import com.datagent.agentv2.persistence.mapper.memory.MemoryReviewJobMapper;
import com.datagent.agentv2.persistence.mapping.MemoryPersistenceMapper;
import com.datagent.agentv2.platform.context.OperatorContext;
import com.datagent.agentv2.platform.context.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 审查任务锁与 CAS 原语；完整领取流程由后台业务服务在同一事务中编排。 */
@Service
@RequiredArgsConstructor
public class MemoryReviewJobService
        extends ServiceImpl<MemoryReviewJobMapper, MemoryReviewJobEntity> {
    private final MemoryPersistenceMapper models;

    public MemoryReviewJob find(String jobId) {
        return map(
                lambdaQuery()
                        .eq(MemoryReviewJobEntity::getUserId, OperatorContext.requiredUserId())
                        .eq(MemoryReviewJobEntity::getJobId, jobId)
                        .one());
    }

    public MemoryReviewJob lock(String jobId) {
        return map(
                lambdaQuery()
                        .eq(MemoryReviewJobEntity::getUserId, OperatorContext.requiredUserId())
                        .eq(MemoryReviewJobEntity::getJobId, jobId)
                        .last("FOR UPDATE")
                        .one());
    }

    public MemoryReviewJob insert(MemoryReviewJob job) {
        var entity = models.toEntity(job);
        save(entity);
        return models.toModel(entity);
    }

    public boolean compareAndSet(MemoryReviewJob job, long expectedToken) {
        // 可空字段必须通过 Wrapper 显式 SET；否则默认非空策略会留下旧 owner 和租约。
        return baseMapper.update(
                        MemoryReviewJobEntity.create(),
                        Wrappers.lambdaUpdate(MemoryReviewJobEntity.class)
                                .eq(
                                        MemoryReviewJobEntity::getUserId,
                                        OperatorContext.requiredUserId())
                                .eq(MemoryReviewJobEntity::getJobId, job.jobId())
                                .eq(MemoryReviewJobEntity::getFencingToken, expectedToken)
                                .set(MemoryReviewJobEntity::getConversationId, job.conversationId())
                                .set(MemoryReviewJobEntity::getLearningEpoch, job.learningEpoch())
                                .set(
                                        MemoryReviewJobEntity::getReviewedThroughSequence,
                                        job.reviewedThroughSequence())
                                .set(
                                        MemoryReviewJobEntity::getPendingThroughSequence,
                                        job.pendingThroughSequence())
                                .set(
                                        MemoryReviewJobEntity::getClaimedThroughSequence,
                                        job.claimedThroughSequence())
                                .set(
                                        MemoryReviewJobEntity::getDirtyGeneration,
                                        job.dirtyGeneration())
                                .set(
                                        MemoryReviewJobEntity::getClaimedGeneration,
                                        job.claimedGeneration())
                                .set(MemoryReviewJobEntity::getStatus, job.status().name())
                                .set(MemoryReviewJobEntity::getAvailableAt, job.availableAt())
                                .set(MemoryReviewJobEntity::getFailureCount, job.failureCount())
                                .set(MemoryReviewJobEntity::getFailureReason, job.failureReason())
                                .set(MemoryReviewJobEntity::getOwner, job.owner())
                                .set(MemoryReviewJobEntity::getFencingToken, job.fencingToken())
                                .set(MemoryReviewJobEntity::getLeaseUntil, job.leaseUntil()))
                == 1;
    }

    public boolean validateClaim(MemoryReviewClaim claim) {
        return baseMapper.validateClaim(
                TenantContext.requiredTenantId(),
                OperatorContext.requiredUserId(),
                claim.jobId(),
                claim.conversationId(),
                claim.epoch(),
                claim.jobToken(),
                claim.userToken(),
                claim.owner(),
                claim.claimedGeneration(),
                claim.fromSequence(),
                claim.throughSequence(),
                claim.leaseUntil());
    }

    private MemoryReviewJob map(MemoryReviewJobEntity entity) {
        return entity == null ? null : models.toModel(entity);
    }
}
