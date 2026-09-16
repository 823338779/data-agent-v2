package com.datagent.agentv2.memory.review;

import com.datagent.agentv2.memory.MemoryException;
import com.datagent.agentv2.memory.MemoryReviewClaim;
import com.datagent.agentv2.memory.MemoryReviewJob;
import com.datagent.agentv2.memory.MemoryReviewJob.Status;
import com.datagent.agentv2.memory.MemoryReviewLease;
import com.datagent.agentv2.memory.MemoryState;
import com.datagent.agentv2.memory.MemoryStatus;
import com.datagent.agentv2.memory.config.MemoryProperties;
import com.datagent.agentv2.persistence.service.memory.MemoryReviewJobService;
import com.datagent.agentv2.persistence.service.memory.MemoryReviewLeaseService;
import com.datagent.agentv2.persistence.service.memory.MemoryStateService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * 审查任务的短事务状态机，不进行模型 I/O。所有竞争统一按 state → job → userLease 加锁； 双重 fencing 和数据库真实时钟防止旧 worker
 * 在接管、关闭学习后继续提交。
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class MemoryReviewCoordinator {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final MemoryStateService states;
    private final MemoryReviewJobService jobs;
    private final MemoryReviewLeaseService leases;
    private final MemoryProperties properties;

    /** 无锁选择候选后在统一锁序下重新判断；固定用户只能同时持有一份审查租约。 */
    public List<MemoryReviewClaim> claimDue(String owner, int limit) {
        if (owner == null || owner.isBlank() || limit < 1)
            throw new IllegalArgumentException("领取所有者与数量必须有效");
        var observedState = states.findCurrent();
        if (observedState == null) return List.of();
        LocalDateTime selectedAt = states.currentBusinessTime();
        // 两个候选桶各有独立名额：旧代际取消不必等待 due/TTL，也不让取消积压饿死当前就绪任务。
        var candidates = new ArrayList<>(jobs.findObsolete(observedState.learningEpoch(), limit));
        candidates.addAll(
                jobs.findDue(
                        observedState.learningEpoch(), selectedAt, instant(selectedAt), limit));
        if (candidates.isEmpty()) return List.of();
        // 无锁 epoch 只是候选提示；若此时已切代，按锁内状态取消，本轮未选到的新工作留给下轮。
        var state = states.lockCurrent();
        for (String id : candidates) {
            var job = jobs.lock(id);
            if (job == null) continue;
            // 候选快照之后可能已完成；切代取消只收敛未完成工作，不能改写已提交的完成历史。
            if (job.status() == Status.COMPLETED || job.status() == Status.CANCELLED) continue;
            LocalDateTime now = states.currentBusinessTime();
            if (!state.autoLearningEnabled() || state.learningEpoch() != job.learningEpoch()) {
                // 扫描没有用户 token 凭证，同 owner 可能已领取另一任务，不能据此释放用户租约。
                requireChanged(
                        jobs.compareAndSet(
                                copy(
                                        job,
                                        job.reviewedThroughSequence(),
                                        Status.CANCELLED,
                                        job.availableAt(),
                                        job.failureCount(),
                                        "LEARNING_EPOCH_CHANGED",
                                        null,
                                        null),
                                job.fencingToken()));
                continue;
            }
            if (!due(job, now)) continue;
            // 只有确定要领取时才锁用户租约；此后直接返回，不再倒序获取其他任务锁。
            var lease = leases.lockCurrent();
            if (lease != null
                    && lease.leaseUntil() != null
                    && lease.leaseUntil().isAfter(instant(now))) return List.of();
            Instant until = instant(now).plus(properties.getLeaseDuration());
            long userToken = lease == null ? 1 : lease.fencingToken() + 1;
            var acquired = new MemoryReviewLease(userToken, owner, until);
            if (lease == null) leases.insert(acquired);
            else requireChanged(leases.compareAndSet(acquired, lease.fencingToken()));
            var running =
                    new MemoryReviewJob(
                            job.jobId(),
                            job.conversationId(),
                            job.learningEpoch(),
                            job.reviewedThroughSequence(),
                            job.pendingThroughSequence(),
                            job.pendingThroughSequence(),
                            job.dirtyGeneration(),
                            job.dirtyGeneration(),
                            Status.RUNNING,
                            job.availableAt(),
                            job.failureCount(),
                            null,
                            owner,
                            job.fencingToken() + 1,
                            until);
            requireChanged(jobs.compareAndSet(running, job.fencingToken()));
            // 同一固定身份仅允许一个后台模型循环，避免持有一份用户租约却返回多个并行批次。
            return List.of(
                    new MemoryReviewClaim(
                            job.jobId(),
                            job.conversationId(),
                            job.learningEpoch(),
                            running.fencingToken(),
                            userToken,
                            owner,
                            running.claimedGeneration(),
                            running.reviewedThroughSequence(),
                            running.claimedThroughSequence(),
                            until));
        }
        return List.of();
    }

    /** 续租仅延长数据库里的截止时刻，原 claim 继续有效；绝不复活已经过期的 owner。 */
    public boolean renew(MemoryReviewClaim claim) {
        var locked = lock(claim.jobId());
        if (!matches(locked, claim)) return false;
        if (!currentEpoch(locked)) {
            cancel(locked);
            return false;
        }
        if (!valid(claim)) return false;
        var job = locked.job();
        Instant until = instant(states.currentBusinessTime()).plus(properties.getLeaseDuration());
        if (until.isBefore(job.leaseUntil())) until = job.leaseUntil();
        return atomicActiveUpdate(
                copy(
                        job,
                        job.reviewedThroughSequence(),
                        Status.RUNNING,
                        job.availableAt(),
                        job.failureCount(),
                        job.failureReason(),
                        job.owner(),
                        until),
                new MemoryReviewLease(claim.userToken(), claim.owner(), until),
                claim);
    }

    /** 只确认冻结范围；返回已持久化状态供 worker 区分继续、成功及取消，失去租约明确失败。 */
    public Status finish(MemoryReviewClaim claim, MemoryReviewProgress progress) {
        if (progress.reviewedThroughSequence() < claim.fromSequence()
                || progress.reviewedThroughSequence() > claim.throughSequence())
            throw new IllegalArgumentException("审查进度超出冻结范围");
        var locked = lock(claim.jobId());
        if (!matches(locked, claim)) throw lost();
        if (!currentEpoch(locked)) {
            cancel(locked);
            return Status.CANCELLED;
        }
        if (!valid(claim)) throw lost();
        var job = locked.job();
        Status status =
                job.dirtyGeneration() > claim.claimedGeneration()
                                || progress.reviewedThroughSequence() < job.pendingThroughSequence()
                        ? Status.PENDING
                        : Status.COMPLETED;
        requireChanged(
                settle(
                        locked,
                        claim,
                        progress.reviewedThroughSequence(),
                        status,
                        states.currentBusinessTime(),
                        0,
                        null));
        return status;
    }

    /** 失败保留连续游标；暂时失败有界重试，输入阻塞立即等待新事件激活。 */
    public void fail(MemoryReviewClaim claim, String reason, boolean blocked) {
        var locked = lock(claim.jobId());
        if (!matches(locked, claim)) return;
        if (!currentEpoch(locked)) {
            cancel(locked);
            return;
        }
        if (!valid(claim)) return;
        int failures = locked.job().failureCount() + 1;
        settle(
                locked,
                claim,
                locked.job().reviewedThroughSequence(),
                blocked || failures >= properties.getMaxAttempts() ? Status.BLOCKED : Status.FAILED,
                states.currentBusinessTime().plus(properties.getRetryDelay()),
                failures,
                reason);
    }

    /** worker 提前退出时退回待处理；保留 token 计数，旧 finally 不能释放接管者的租约。 */
    public void release(MemoryReviewClaim claim) {
        var locked = lock(claim.jobId());
        if (!matches(locked, claim)) return;
        if (!currentEpoch(locked)) {
            cancel(locked);
            return;
        }
        if (!valid(claim)) return;
        var job = locked.job();
        settle(
                locked,
                claim,
                job.reviewedThroughSequence(),
                Status.PENDING,
                states.currentBusinessTime(),
                job.failureCount(),
                job.failureReason());
    }

    private Locked lock(String id) {
        var state = states.lockCurrent();
        var job = jobs.lock(id);
        var lease = job == null ? null : leases.lockCurrent();
        return new Locked(state, job, lease);
    }

    private boolean valid(MemoryReviewClaim claim) {
        // Mapper 的 clock_timestamp 校验清除一级缓存，续租之后的旧 claim 也按最新截止时刻验证。
        return jobs.validateClaim(claim) && leases.validateClaim(claim);
    }

    private static boolean matches(Locked locked, MemoryReviewClaim claim) {
        var job = locked.job();
        var lease = locked.lease();
        return job != null
                && lease != null
                && job.status() == Status.RUNNING
                && job.learningEpoch() == claim.epoch()
                && job.fencingToken() == claim.jobToken()
                && lease.fencingToken() == claim.userToken()
                && Objects.equals(job.owner(), claim.owner())
                && Objects.equals(lease.owner(), claim.owner())
                && job.conversationId().equals(claim.conversationId())
                && job.claimedGeneration() == claim.claimedGeneration()
                && job.reviewedThroughSequence() == claim.fromSequence()
                && job.claimedThroughSequence() == claim.throughSequence();
    }

    private static boolean currentEpoch(Locked locked) {
        return locked.state().autoLearningEnabled()
                && locked.state().learningEpoch() == locked.job().learningEpoch();
    }

    private static boolean due(MemoryReviewJob job, LocalDateTime now) {
        return ((job.status() == Status.PENDING || job.status() == Status.FAILED)
                        && !job.availableAt().isAfter(now))
                || (job.status() == Status.RUNNING
                        && job.leaseUntil() != null
                        && !job.leaseUntil().isAfter(instant(now)));
    }

    private void cancel(Locked locked) {
        var job = locked.job();
        // 仅供已匹配双 token 的旧代际 claim；取消即使租约已过期也可收敛，不能误称完成。
        requireChanged(
                jobs.compareAndSet(
                        copy(
                                job,
                                job.reviewedThroughSequence(),
                                Status.CANCELLED,
                                job.availableAt(),
                                job.failureCount(),
                                "LEARNING_EPOCH_CHANGED",
                                null,
                                null),
                        job.fencingToken()));
        var lease = locked.lease();
        requireChanged(
                leases.compareAndSet(
                        new MemoryReviewLease(lease.fencingToken(), null, null),
                        lease.fencingToken()));
    }

    private boolean settle(
            Locked locked,
            MemoryReviewClaim claim,
            long reviewed,
            Status status,
            LocalDateTime dueAt,
            int failures,
            String reason) {
        var job = locked.job();
        return atomicActiveUpdate(
                copy(job, reviewed, status, dueAt, failures, reason, null, null),
                new MemoryReviewLease(claim.userToken(), null, null),
                claim);
    }

    private boolean atomicActiveUpdate(
            MemoryReviewJob job, MemoryReviewLease lease, MemoryReviewClaim claim) {
        if (jobs.compareAndSetActive(job, claim) && leases.compareAndSetActive(lease, claim))
            return true;
        // 第二条 UPDATE 也可能刚好过期；回滚整个短事务，且 fail/release 不掩盖 worker 原有失败。
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        return false;
    }

    private static MemoryReviewJob copy(
            MemoryReviewJob job,
            long reviewed,
            Status status,
            LocalDateTime dueAt,
            int failures,
            String reason,
            String owner,
            Instant leaseUntil) {
        return new MemoryReviewJob(
                job.jobId(),
                job.conversationId(),
                job.learningEpoch(),
                reviewed,
                job.pendingThroughSequence(),
                job.claimedThroughSequence(),
                job.dirtyGeneration(),
                job.claimedGeneration(),
                status,
                dueAt,
                failures,
                reason,
                owner,
                job.fencingToken(),
                leaseUntil);
    }

    private static Instant instant(LocalDateTime time) {
        return time.atZone(BUSINESS_ZONE).toInstant();
    }

    private static void requireChanged(boolean changed) {
        if (!changed) throw lost();
    }

    private static MemoryException lost() {
        return new MemoryException(MemoryStatus.LEASE_LOST, "审查租约已失效");
    }

    private record Locked(MemoryState state, MemoryReviewJob job, MemoryReviewLease lease) {}
}
