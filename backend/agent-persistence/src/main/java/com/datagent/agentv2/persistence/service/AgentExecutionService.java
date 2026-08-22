package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.persistence.entity.AgentExecutionEntity;
import com.datagent.agentv2.persistence.mapping.ConversationPersistenceMapper;
import com.datagent.agentv2.persistence.service.mapper.AgentExecutionMapper;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentExecutionService extends ServiceImpl<AgentExecutionMapper, AgentExecutionEntity> {

    private static final String COLUMN_OWNER_USER_ID = "owner_user_id";
    private static final String COLUMN_QUEUED_AT_MIN = "min(queued_at)";
    private static final String COLUMN_STATUS = "status";
    private static final String FAILURE_CODE_DEADLINE_EXCEEDED =
            "EXECUTION_DEADLINE_EXCEEDED";
    private static final String FAILURE_CODE_RECOVERY_EXHAUSTED = "RECOVERY_EXHAUSTED";
    private static final String LOCK_FOR_UPDATE = "FOR UPDATE";
    private static final String LOCK_NEXT_AVAILABLE = "LIMIT 1 FOR UPDATE SKIP LOCKED";
    private static final String QUERY_LIMIT_PREFIX = "LIMIT ";
    private static final String PHASE_COMPLETED = "COMPLETED";
    private static final String PHASE_FAILED = "FAILED";
    private static final String PHASE_RECEIVED = "RECEIVED";
    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_CANCELLED = "cancelled";
    private static final String STATUS_DONE = "done";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_WAITING_FOR_USER = "waiting_for_user";
    private static final String STATUS_WAITING_CONFIRMATION = "waiting_confirmation";
    private static final String SET_ACTIVE_EXECUTION_DURATION =
            """
            active_execution_ms = active_execution_ms + greatest(0,
                (extract(epoch from ({0} - active_segment_started_at)) * 1000)::bigint)
            """;
    private static final String SET_STARTED_AT_IF_ABSENT =
            "started_at = coalesce(started_at, {0})";
    private static final String TOUCH_UPDATED_AT = "updated_at = updated_at";

    private final ConversationPersistenceMapper models;

    public AgentExecution lockById(String executionId) {
        return models.toModel(
                lambdaQuery()
                        .eq(AgentExecutionEntity::getExecutionId, executionId)
                        .last(LOCK_FOR_UPDATE)
                        .one());
    }

    public AgentExecution findByExecutionId(String executionId) {
        return models.toModel(
                lambdaQuery().eq(AgentExecutionEntity::getExecutionId, executionId).one());
    }

    public boolean save(AgentExecution execution) {
        AgentExecutionEntity entity = models.toEntity(execution);
        boolean saved = super.save(entity);
        if (saved) {
            models.updateModel(entity, execution);
        }
        return saved;
    }

    public boolean updateById(AgentExecution execution) {
        AgentExecutionEntity entity =
                lambdaQuery()
                        .eq(AgentExecutionEntity::getExecutionId, execution.getExecutionId())
                        .one();
        if (entity == null) {
            return false;
        }
        models.updateEntity(execution, entity);
        boolean updated = super.updateById(entity);
        if (updated) {
            models.updateModel(entity, execution);
        }
        return updated;
    }

    public List<String> queuedOwnerUserIds(int limit) {
        // 分组 Wrapper 只投影 owner，并按最早排队时间保持跨用户公平顺序。
        QueryWrapper<AgentExecutionEntity> query = Wrappers.query();
        query.select(COLUMN_OWNER_USER_ID)
                .eq(COLUMN_STATUS, STATUS_QUEUED)
                .groupBy(COLUMN_OWNER_USER_ID)
                .orderByAsc(COLUMN_QUEUED_AT_MIN, COLUMN_OWNER_USER_ID)
                .last(QUERY_LIMIT_PREFIX + Math.max(0, limit));
        return baseMapper.selectObjs(query).stream().map(String.class::cast).toList();
    }

    public int countRunningExecutionsForUser(String userId) {
        return Math.toIntExact(
                lambdaQuery()
                        .eq(AgentExecutionEntity::getOwnerUserId, userId)
                        .eq(AgentExecutionEntity::getStatus, STATUS_RUNNING)
                        .count());
    }

    public AgentExecution lockOldestQueuedForUser(String userId) {
        return models.toModel(
                lambdaQuery()
                        .eq(AgentExecutionEntity::getOwnerUserId, userId)
                        .eq(AgentExecutionEntity::getStatus, STATUS_QUEUED)
                        .orderByAsc(AgentExecutionEntity::getQueuedAt)
                        .orderByAsc(AgentExecutionEntity::getId)
                        .last(LOCK_NEXT_AVAILABLE)
                        .one());
    }

    public boolean claimQueued(
            AgentExecution execution,
            long executionAttemptNumber,
            String ownerInstanceId,
            Instant now,
            Instant leaseExpiresAt,
            Instant hardDeadlineAt) {
        // 领取通过 queued 状态和期望版本原子竞争，无需额外锁定状态迁移本身。
        AgentExecutionEntity changes = new AgentExecutionEntity();
        changes.setStatus(STATUS_RUNNING);
        changes.setPhase(PHASE_RECEIVED);
        changes.setExecutionAttemptNumber(executionAttemptNumber);
        changes.setOwnerInstanceId(ownerInstanceId);
        changes.setLeaseExpiresAt(leaseExpiresAt);
        changes.setHeartbeatAt(now);
        changes.setActiveSegmentStartedAt(now);
        changes.setHardDeadlineAt(hardDeadlineAt);
        return baseMapper.update(
                        changes,
                        Wrappers.lambdaUpdate(AgentExecutionEntity.class)
                                .eq(
                                        AgentExecutionEntity::getExecutionId,
                                        execution.getExecutionId())
                                .eq(AgentExecutionEntity::getStatus, STATUS_QUEUED)
                                .eq(AgentExecutionEntity::getVersion, execution.getVersion())
                                .setSql(SET_STARTED_AT_IF_ABSENT, now)
                                .setIncrBy(AgentExecutionEntity::getVersion, 1))
                == 1;
    }

    public boolean renewLease(
            String executionId,
            String ownerInstanceId,
            long executionAttemptNumber,
            Instant now,
            Instant leaseExpiresAt) {
        // owner、attempt 和硬截止时间共同构成续租 fencing 条件。
        AgentExecutionEntity changes = new AgentExecutionEntity();
        changes.setHeartbeatAt(now);
        changes.setLeaseExpiresAt(leaseExpiresAt);
        return baseMapper.update(
                        changes,
                        ownedExecutionUpdate(executionId, ownerInstanceId, executionAttemptNumber)
                                .gt(AgentExecutionEntity::getHardDeadlineAt, now)
                                .setIncrBy(AgentExecutionEntity::getVersion, 1))
                == 1;
    }

    public boolean fenceOwned(
            String executionId, String ownerInstanceId, long executionAttemptNumber, Instant now) {
        // no-op UPDATE 在当前事务内获取行写锁，并以相同条件验证 owner 尚未失效。
        return baseMapper.update(
                        null,
                        ownedExecutionUpdate(executionId, ownerInstanceId, executionAttemptNumber)
                                .gt(AgentExecutionEntity::getHardDeadlineAt, now)
                                .setSql(TOUCH_UPDATED_AT))
                == 1;
    }

    public boolean cancellationRequested(
            String executionId, String ownerInstanceId, long executionAttemptNumber) {
        return lambdaQuery()
                        .eq(AgentExecutionEntity::getExecutionId, executionId)
                        .eq(AgentExecutionEntity::getStatus, STATUS_RUNNING)
                        .eq(AgentExecutionEntity::getOwnerInstanceId, ownerInstanceId)
                        .eq(
                                AgentExecutionEntity::getExecutionAttemptNumber,
                                executionAttemptNumber)
                        .isNotNull(AgentExecutionEntity::getCancellationRequestedAt)
                        .count()
                == 1L;
    }

    public boolean cancelOwnedIfRequested(
            String executionId, String ownerInstanceId, long executionAttemptNumber, Instant now) {
        // 取消终态与活跃时长结算在同一条条件 UPDATE 内完成。
        AgentExecutionEntity changes = new AgentExecutionEntity();
        changes.setStatus(STATUS_CANCELLED);
        changes.setCompletedAt(now);
        LambdaUpdateWrapper<AgentExecutionEntity> update =
                clearOwnership(
                                ownedExecutionUpdate(
                                        executionId, ownerInstanceId, executionAttemptNumber))
                        .isNotNull(AgentExecutionEntity::getCancellationRequestedAt)
                        .setSql(SET_ACTIVE_EXECUTION_DURATION, now)
                        .setIncrBy(AgentExecutionEntity::getVersion, 1);
        return baseMapper.update(changes, update)
                == 1;
    }

    /** 显式清空未进入 worker 的执行租约字段，并用乐观锁提交直接取消。 */
    public boolean cancelLocked(AgentExecution execution, Instant now) {
        // 非 running 状态以版本 fencing 直接转为 cancelled，并显式清空可能残留的租约字段。
        AgentExecutionEntity changes = new AgentExecutionEntity();
        changes.setStatus(STATUS_CANCELLED);
        changes.setCompletedAt(now);
        boolean cancelled =
                baseMapper.update(
                                changes,
                                clearOwnership(
                                                Wrappers.lambdaUpdate(
                                                                AgentExecutionEntity.class)
                                                        .eq(
                                                                AgentExecutionEntity::getExecutionId,
                                                                execution.getExecutionId())
                                                        .eq(
                                                                AgentExecutionEntity::getVersion,
                                                                execution.getVersion())
                                                        .in(
                                                                AgentExecutionEntity::getStatus,
                                                                STATUS_QUEUED,
                                                                STATUS_WAITING_FOR_USER,
                                                                STATUS_WAITING_CONFIRMATION))
                                        .setIncrBy(AgentExecutionEntity::getVersion, 1))
                        == 1;
        if (cancelled) {
            // Wrapper UPDATE 不会回填业务模型；同步内存状态，确保当前事务响应返回取消终态。
            execution.cancelDirectly(STATUS_CANCELLED, now);
            execution.setVersion(execution.getVersion() + 1L);
        }
        return cancelled;
    }

    /** 使用 owner/attempt fencing 把运行中执行转换为通用失败终态。 */
    public boolean failOwned(
            String executionId,
            String ownerInstanceId,
            long executionAttemptNumber,
            String failureCode,
            String failureDetail,
            Instant now) {
        // 运行中失败在 owner fencing 内结算活跃时长并清空租约。
        AgentExecutionEntity changes = failedChanges(failureCode, failureDetail, now);
        LambdaUpdateWrapper<AgentExecutionEntity> update =
                clearOwnership(
                                ownedExecutionUpdate(
                                        executionId, ownerInstanceId, executionAttemptNumber))
                        .setSql(SET_ACTIVE_EXECUTION_DURATION, now)
                        .setIncrBy(AgentExecutionEntity::getVersion, 1);
        return baseMapper.update(changes, update)
                == 1;
    }

    /** 对已经由恢复调度器加锁的执行，以版本和旧 owner 条件提交失败终态。 */
    public boolean failLocked(
            AgentExecution execution,
            String failureCode,
            String failureDetail,
            long activeExecutionMs,
            Instant now) {
        // 已锁定恢复分支仍保留版本、旧 owner 和 attempt 条件，避免锁边界被误用。
        AgentExecutionEntity changes = failedChanges(failureCode, failureDetail, now);
        changes.setActiveExecutionMs(activeExecutionMs);
        return baseMapper.update(
                        changes,
                        clearOwnership(
                                        ownedExecutionUpdate(
                                                        execution.getExecutionId(),
                                                        execution.getOwnerInstanceId(),
                                                        execution.getExecutionAttemptNumber())
                                                .eq(
                                                        AgentExecutionEntity::getVersion,
                                                        execution.getVersion()))
                                .setIncrBy(AgentExecutionEntity::getVersion, 1))
                == 1;
    }

    public boolean failOwnedPastDeadline(
            String executionId, String ownerInstanceId, long executionAttemptNumber, Instant now) {
        // 仅硬截止时间已经到达的当前 owner 可以提交 deadline 失败终态。
        AgentExecutionEntity changes =
                failedChanges(FAILURE_CODE_DEADLINE_EXCEEDED, null, now);
        LambdaUpdateWrapper<AgentExecutionEntity> update =
                clearOwnership(
                                ownedExecutionUpdate(
                                        executionId, ownerInstanceId, executionAttemptNumber))
                        .le(AgentExecutionEntity::getHardDeadlineAt, now)
                        .setSql(SET_ACTIVE_EXECUTION_DURATION, now)
                        .setIncrBy(AgentExecutionEntity::getVersion, 1);
        return baseMapper.update(changes, update)
                == 1;
    }

    public AgentExecution lockNextExpired(Instant now) {
        return models.toModel(
                lambdaQuery()
                        .eq(AgentExecutionEntity::getStatus, STATUS_RUNNING)
                        .le(AgentExecutionEntity::getLeaseExpiresAt, now)
                        .orderByAsc(AgentExecutionEntity::getLeaseExpiresAt)
                        .orderByAsc(AgentExecutionEntity::getId)
                        .last(LOCK_NEXT_AVAILABLE)
                        .one());
    }

    public boolean takeoverExpired(
            AgentExecution execution,
            String newOwnerInstanceId,
            long newExecutionAttemptNumber,
            long activeExecutionMs,
            Instant now,
            Instant leaseExpiresAt,
            Instant hardDeadlineAt) {
        // 接管在旧 owner/version/过期 lease 条件下原子安装新的 ownership。
        AgentExecutionEntity changes = new AgentExecutionEntity();
        changes.setOwnerInstanceId(newOwnerInstanceId);
        changes.setExecutionAttemptNumber(newExecutionAttemptNumber);
        changes.setActiveExecutionMs(activeExecutionMs);
        changes.setActiveSegmentStartedAt(now);
        changes.setHeartbeatAt(now);
        changes.setLeaseExpiresAt(leaseExpiresAt);
        changes.setHardDeadlineAt(hardDeadlineAt);
        return baseMapper.update(
                        changes,
                        ownedExecutionUpdate(
                                        execution.getExecutionId(),
                                        execution.getOwnerInstanceId(),
                                        execution.getExecutionAttemptNumber())
                                .eq(AgentExecutionEntity::getVersion, execution.getVersion())
                                .le(AgentExecutionEntity::getLeaseExpiresAt, now)
                                .setIncrBy(AgentExecutionEntity::getRecoveryAttemptCount, 1)
                                .setIncrBy(AgentExecutionEntity::getVersion, 1))
                == 1;
    }

    public boolean failExpiredBudget(
            AgentExecution execution, long activeExecutionMs, Instant now) {
        // 已锁定的过期执行按期望版本提交预算耗尽终态。
        AgentExecutionEntity changes =
                failedChanges(FAILURE_CODE_DEADLINE_EXCEEDED, null, now);
        changes.setActiveExecutionMs(activeExecutionMs);
        return baseMapper.update(
                        changes,
                        clearOwnership(
                                        ownedExecutionUpdate(
                                                        execution.getExecutionId(),
                                                        execution.getOwnerInstanceId(),
                                                        execution.getExecutionAttemptNumber())
                                                .eq(
                                                        AgentExecutionEntity::getVersion,
                                                        execution.getVersion()))
                                .setIncrBy(AgentExecutionEntity::getVersion, 1))
                == 1;
    }

    public AgentExecution findRequest(
            String conversationSessionId, String requestType, String idempotencyKey) {
        return models.toModel(
                lambdaQuery()
                        .eq(AgentExecutionEntity::getConversationSessionId, conversationSessionId)
                        .eq(AgentExecutionEntity::getRequestType, requestType)
                        .eq(AgentExecutionEntity::getRequestIdempotencyKey, idempotencyKey)
                        .one());
    }

    public boolean hasActiveExecution(String conversationSessionId) {
        return lambdaQuery()
                        .eq(AgentExecutionEntity::getConversationSessionId, conversationSessionId)
                        .in(
                                AgentExecutionEntity::getStatus,
                                STATUS_QUEUED,
                                STATUS_RUNNING,
                                STATUS_WAITING_FOR_USER,
                                STATUS_WAITING_CONFIRMATION)
                        .count()
                > 0;
    }

    public boolean completeOwned(
            String executionId, String ownerInstanceId, long executionAttemptNumber, Instant now) {
        // 正常完成和活跃时长结算共享同一 owner-fenced Wrapper UPDATE。
        AgentExecutionEntity changes = new AgentExecutionEntity();
        changes.setStatus(STATUS_DONE);
        changes.setPhase(PHASE_COMPLETED);
        changes.setCompletedAt(now);
        LambdaUpdateWrapper<AgentExecutionEntity> update =
                clearOwnership(
                                ownedExecutionUpdate(
                                        executionId, ownerInstanceId, executionAttemptNumber))
                        .setSql(SET_ACTIVE_EXECUTION_DURATION, now)
                        .setIncrBy(AgentExecutionEntity::getVersion, 1);
        return baseMapper.update(changes, update)
                == 1;
    }

    public boolean failExpiredRecovery(
            AgentExecution execution, long activeExecutionMs, Instant now) {
        // 恢复次数耗尽只关闭仍由旧 owner 持有且 lease 已过期的执行。
        AgentExecutionEntity changes =
                failedChanges(FAILURE_CODE_RECOVERY_EXHAUSTED, null, now);
        changes.setActiveExecutionMs(activeExecutionMs);
        return baseMapper.update(
                        changes,
                        clearOwnership(
                                        ownedExecutionUpdate(
                                                        execution.getExecutionId(),
                                                        execution.getOwnerInstanceId(),
                                                        execution.getExecutionAttemptNumber())
                                                .eq(
                                                        AgentExecutionEntity::getVersion,
                                                        execution.getVersion())
                                                .le(
                                                        AgentExecutionEntity::getLeaseExpiresAt,
                                                        now))
                                .setIncrBy(AgentExecutionEntity::getVersion, 1))
                == 1;
    }

    /** 构造所有运行中 owner-fenced 更新共用的状态和所有权条件。 */
    private static LambdaUpdateWrapper<AgentExecutionEntity> ownedExecutionUpdate(
            String executionId, String ownerInstanceId, long executionAttemptNumber) {
        return Wrappers.lambdaUpdate(AgentExecutionEntity.class)
                .eq(AgentExecutionEntity::getExecutionId, executionId)
                .eq(AgentExecutionEntity::getStatus, STATUS_RUNNING)
                .eq(AgentExecutionEntity::getOwnerInstanceId, ownerInstanceId)
                .eq(AgentExecutionEntity::getExecutionAttemptNumber, executionAttemptNumber);
    }

    /** 显式清空全部运行时 ownership 字段，避免终态残留可被误判为活跃租约。 */
    private static LambdaUpdateWrapper<AgentExecutionEntity> clearOwnership(
            LambdaUpdateWrapper<AgentExecutionEntity> update) {
        return update.set(AgentExecutionEntity::getOwnerInstanceId, null)
                .set(AgentExecutionEntity::getActiveSegmentStartedAt, null)
                .set(AgentExecutionEntity::getHeartbeatAt, null)
                .set(AgentExecutionEntity::getLeaseExpiresAt, null)
                .set(AgentExecutionEntity::getHardDeadlineAt, null);
    }

    /** 构造失败终态的非空字段；租约清理由对应条件 Wrapper 统一处理。 */
    private static AgentExecutionEntity failedChanges(
            String failureCode, String failureDetail, Instant now) {
        AgentExecutionEntity changes = new AgentExecutionEntity();
        changes.setStatus(STATUS_FAILED);
        changes.setPhase(PHASE_FAILED);
        changes.setFailureCode(failureCode);
        changes.setFailureDetail(failureDetail);
        changes.setFailedAt(now);
        return changes;
    }

    /** 按会话读取执行历史，最新执行排在最前。 */
    public List<AgentExecution> findByConversationSessionIdOrderByCreatedAtDesc(
            String conversationSessionId) {
        return models.toExecutionModels(
                lambdaQuery()
                        .eq(AgentExecutionEntity::getConversationSessionId, conversationSessionId)
                        .orderByDesc(AgentExecutionEntity::getCreatedAt)
                        .list());
    }

    /** 按一组执行标识批量读取业务模型。 */
    public List<AgentExecution> findByExecutionIds(Collection<String> executionIds) {
        return models.toExecutionModels(
                lambdaQuery().in(AgentExecutionEntity::getExecutionId, executionIds).list());
    }

    /** 按会话集合读取执行，并保持每个会话内部的创建时间倒序。 */
    public List<AgentExecution> findByConversationSessionIdsOrderByCreatedAtDesc(
            Collection<String> conversationSessionIds) {
        return models.toExecutionModels(
                lambdaQuery()
                        .in(AgentExecutionEntity::getConversationSessionId, conversationSessionIds)
                        .orderByDesc(AgentExecutionEntity::getCreatedAt)
                        .list());
    }
}
