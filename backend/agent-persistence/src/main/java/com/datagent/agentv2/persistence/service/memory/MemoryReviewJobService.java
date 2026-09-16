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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 审查任务锁与 CAS 原语；完整领取流程由后台业务服务在同一事务中编排。 */
@Service
@RequiredArgsConstructor
public class MemoryReviewJobService
        extends ServiceImpl<MemoryReviewJobMapper, MemoryReviewJobEntity> {
    private final MemoryPersistenceMapper models;

    /** 无锁读取候选；领取方必须先锁 state 再重读任务，不能把此快照当作所有权。 */
    public List<String> findDue(LocalDateTime now, Instant instant, int limit) {
        return lambdaQuery()
                .eq(MemoryReviewJobEntity::getUserId, OperatorContext.requiredUserId())
                .and(
                        q ->
                                q.in(MemoryReviewJobEntity::getStatus, "PENDING", "FAILED")
                                        .le(MemoryReviewJobEntity::getAvailableAt, now)
                                        .or(
                                                r ->
                                                        r.eq(
                                                                        MemoryReviewJobEntity
                                                                                ::getStatus,
                                                                        "RUNNING")
                                                                .le(
                                                                        MemoryReviewJobEntity
                                                                                ::getLeaseUntil,
                                                                        instant)))
                .orderByAsc(MemoryReviewJobEntity::getAvailableAt)
                .orderByAsc(MemoryReviewJobEntity::getJobId)
                .last("LIMIT " + Math.max(1, limit))
                .list()
                .stream()
                .map(MemoryReviewJobEntity::getJobId)
                .toList();
    }

    /**
     * 调用方必须先持有固定 state 行锁；由此序列化首次插入和同会话标记，复用 MP 而无需自定义 upsert。 更新仍显式比较旧 epoch 与
     * token，低代际事件不扩大新任务；新代际清空旧所有权并递增 fencing。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markCompleted(
            String jobId,
            String conversationId,
            long epoch,
            long fromSequence,
            long throughSequence,
            LocalDateTime dueAt) {
        var old =
                map(
                        lambdaQuery()
                                .eq(
                                        MemoryReviewJobEntity::getUserId,
                                        OperatorContext.requiredUserId())
                                .eq(MemoryReviewJobEntity::getConversationId, conversationId)
                                .last("FOR UPDATE")
                                .one());
        if (old != null && old.learningEpoch() > epoch) return;
        if (old == null || old.learningEpoch() < epoch) {
            var next =
                    new MemoryReviewJob(
                            old == null ? jobId : old.jobId(),
                            conversationId,
                            epoch,
                            fromSequence,
                            throughSequence,
                            fromSequence,
                            1,
                            0,
                            MemoryReviewJob.Status.PENDING,
                            dueAt,
                            0,
                            null,
                            null,
                            old == null ? 0 : old.fencingToken() + 1,
                            null);
            if (old == null) insert(next);
            else requireChanged(compareAndSet(next, old.fencingToken(), old.learningEpoch()));
            return;
        }
        boolean running = old.status() == MemoryReviewJob.Status.RUNNING;
        var next =
                new MemoryReviewJob(
                        old.jobId(),
                        conversationId,
                        epoch,
                        old.reviewedThroughSequence(),
                        Math.max(old.pendingThroughSequence(), throughSequence),
                        old.claimedThroughSequence(),
                        old.dirtyGeneration() + 1,
                        old.claimedGeneration(),
                        running ? old.status() : MemoryReviewJob.Status.PENDING,
                        dueAt,
                        running ? old.failureCount() : 0,
                        running ? old.failureReason() : null,
                        running ? old.owner() : null,
                        old.fencingToken(),
                        running ? old.leaseUntil() : null);
        requireChanged(compareAndSet(next, old.fencingToken(), old.learningEpoch()));
    }

    private static void requireChanged(boolean changed) {
        if (!changed) throw new IllegalStateException("审查标记代际竞争失败");
    }

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
        return compareAndSet(job, expectedToken, job.learningEpoch());
    }

    private boolean compareAndSet(MemoryReviewJob job, long expectedToken, long expectedEpoch) {
        return compareAndSet(job, expectedToken, expectedEpoch, null);
    }

    /** 提交时再核验旧 owner、冻结边界和真实时钟，堵住预校验到 UPDATE 之间的过期窗口。 */
    public boolean compareAndSetActive(MemoryReviewJob job, MemoryReviewClaim claim) {
        return compareAndSet(job, claim.jobToken(), claim.epoch(), claim);
    }

    private boolean compareAndSet(
            MemoryReviewJob job, long expectedToken, long expectedEpoch, MemoryReviewClaim claim) {
        // 可空字段必须通过 Wrapper 显式 SET；否则默认非空策略会留下旧 owner 和租约。
        var update =
                Wrappers.lambdaUpdate(MemoryReviewJobEntity.class)
                        .eq(MemoryReviewJobEntity::getUserId, OperatorContext.requiredUserId())
                        .eq(MemoryReviewJobEntity::getJobId, job.jobId())
                        .eq(MemoryReviewJobEntity::getFencingToken, expectedToken)
                        .eq(MemoryReviewJobEntity::getLearningEpoch, expectedEpoch)
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
                        .set(MemoryReviewJobEntity::getDirtyGeneration, job.dirtyGeneration())
                        .set(MemoryReviewJobEntity::getClaimedGeneration, job.claimedGeneration())
                        .set(MemoryReviewJobEntity::getStatus, job.status().name())
                        .set(MemoryReviewJobEntity::getAvailableAt, job.availableAt())
                        .set(MemoryReviewJobEntity::getFailureCount, job.failureCount())
                        .set(MemoryReviewJobEntity::getFailureReason, job.failureReason())
                        .set(MemoryReviewJobEntity::getOwner, job.owner())
                        .set(MemoryReviewJobEntity::getFencingToken, job.fencingToken())
                        .set(MemoryReviewJobEntity::getLeaseUntil, job.leaseUntil());
        if (claim != null) {
            update.eq(MemoryReviewJobEntity::getStatus, MemoryReviewJob.Status.RUNNING.name())
                    .eq(MemoryReviewJobEntity::getConversationId, claim.conversationId())
                    .eq(MemoryReviewJobEntity::getOwner, claim.owner())
                    .eq(MemoryReviewJobEntity::getClaimedGeneration, claim.claimedGeneration())
                    .eq(MemoryReviewJobEntity::getReviewedThroughSequence, claim.fromSequence())
                    .eq(MemoryReviewJobEntity::getClaimedThroughSequence, claim.throughSequence())
                    .ge(MemoryReviewJobEntity::getLeaseUntil, claim.leaseUntil())
                    // PostgreSQL 真实时钟是原子租约条件，应用主机时间和事务起点都不能替代。
                    .apply("lease_until > clock_timestamp()");
        }
        return baseMapper.update(MemoryReviewJobEntity.create(), update) == 1;
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
