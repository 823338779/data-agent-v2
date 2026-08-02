package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.persistence.entity.AgentExecutionEntity;
import com.datagent.agentv2.persistence.mapping.ConversationPersistenceMapper;
import com.datagent.agentv2.persistence.service.mapper.AgentExecutionMapper;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AgentExecutionService extends ServiceImpl<AgentExecutionMapper, AgentExecutionEntity> {

    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_WAITING_FOR_USER = "waiting_for_user";
    private static final String STATUS_WAITING_CONFIRMATION = "waiting_confirmation";

    private final ConversationPersistenceMapper models;

    public AgentExecutionService(ConversationPersistenceMapper models) {
        this.models = models;
    }

    public AgentExecution lockById(String executionId) {
        return models.toModel(baseMapper.selectByIdForUpdate(executionId));
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
        return baseMapper.selectQueuedOwnerUserIds(limit);
    }

    public int countRunningExecutionsForUser(String userId) {
        return baseMapper.countRunningExecutionsByOwnerUserId(userId);
    }

    public AgentExecution lockOldestQueuedForUser(String userId) {
        return models.toModel(baseMapper.selectOldestQueuedForUpdate(userId));
    }

    public boolean claimQueued(
            AgentExecution execution,
            long executionAttemptNumber,
            String ownerInstanceId,
            Instant now,
            Instant leaseExpiresAt,
            Instant hardDeadlineAt) {
        return baseMapper.claimQueued(
                        execution.getExecutionId(),
                        execution.getVersion(),
                        executionAttemptNumber,
                        ownerInstanceId,
                        now,
                        leaseExpiresAt,
                        hardDeadlineAt)
                == 1;
    }

    public boolean renewLease(
            String executionId,
            String ownerInstanceId,
            long executionAttemptNumber,
            Instant now,
            Instant leaseExpiresAt) {
        return baseMapper.renewLease(
                        executionId, ownerInstanceId, executionAttemptNumber, now, leaseExpiresAt)
                == 1;
    }

    public boolean fenceOwned(
            String executionId, String ownerInstanceId, long executionAttemptNumber, Instant now) {
        return baseMapper.fenceOwned(executionId, ownerInstanceId, executionAttemptNumber, now)
                == 1;
    }

    public boolean cancellationRequested(
            String executionId, String ownerInstanceId, long executionAttemptNumber) {
        return baseMapper.countCancellationRequested(
                        executionId, ownerInstanceId, executionAttemptNumber)
                == 1;
    }

    public boolean cancelOwnedIfRequested(
            String executionId, String ownerInstanceId, long executionAttemptNumber, Instant now) {
        return baseMapper.cancelOwnedIfRequested(
                        executionId, ownerInstanceId, executionAttemptNumber, now)
                == 1;
    }

    public boolean failOwnedPastDeadline(
            String executionId, String ownerInstanceId, long executionAttemptNumber, Instant now) {
        return baseMapper.failOwnedPastDeadline(
                        executionId, ownerInstanceId, executionAttemptNumber, now)
                == 1;
    }

    public AgentExecution lockNextExpired(Instant now) {
        return models.toModel(baseMapper.selectExpiredForUpdate(now));
    }

    public boolean takeoverExpired(
            AgentExecution execution,
            String newOwnerInstanceId,
            long newExecutionAttemptNumber,
            long activeExecutionMs,
            Instant now,
            Instant leaseExpiresAt,
            Instant hardDeadlineAt) {
        return baseMapper.takeoverExpired(
                        execution.getExecutionId(),
                        execution.getVersion(),
                        execution.getOwnerInstanceId(),
                        execution.getExecutionAttemptNumber(),
                        newOwnerInstanceId,
                        newExecutionAttemptNumber,
                        activeExecutionMs,
                        now,
                        leaseExpiresAt,
                        hardDeadlineAt)
                == 1;
    }

    public boolean failExpiredBudget(
            AgentExecution execution, long activeExecutionMs, Instant now) {
        return baseMapper.failExpiredBudget(
                        execution.getExecutionId(),
                        execution.getVersion(),
                        execution.getOwnerInstanceId(),
                        execution.getExecutionAttemptNumber(),
                        activeExecutionMs,
                        now)
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
        return baseMapper.completeOwned(executionId, ownerInstanceId, executionAttemptNumber, now)
                == 1;
    }

    public boolean failExpiredRecovery(
            AgentExecution execution, long activeExecutionMs, Instant now) {
        return baseMapper.failExpiredRecovery(
                        execution.getExecutionId(),
                        execution.getVersion(),
                        execution.getOwnerInstanceId(),
                        execution.getExecutionAttemptNumber(),
                        activeExecutionMs,
                        now)
                == 1;
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
