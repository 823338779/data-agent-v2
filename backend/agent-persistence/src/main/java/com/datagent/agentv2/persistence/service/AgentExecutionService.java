package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.AgentExecutionEntity;
import com.datagent.agentv2.persistence.service.mapper.AgentExecutionMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AgentExecutionService extends ServiceImpl<AgentExecutionMapper, AgentExecutionEntity> {

    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_WAITING_FOR_USER = "waiting_for_user";
    private static final String STATUS_WAITING_CONFIRMATION = "waiting_confirmation";

    public AgentExecutionEntity lockById(String executionId) {
        return baseMapper.selectByIdForUpdate(executionId);
    }

    public AgentExecutionEntity findByExecutionId(String executionId) {
        return lambdaQuery().eq(AgentExecutionEntity::getExecutionId, executionId).one();
    }

    public List<String> queuedOwnerUserIds(int limit) {
        return baseMapper.selectQueuedOwnerUserIds(limit);
    }

    public int countRunningExecutionsForUser(String userId) {
        return baseMapper.countRunningExecutionsByOwnerUserId(userId);
    }

    public AgentExecutionEntity lockOldestQueuedForUser(String userId) {
        return baseMapper.selectOldestQueuedForUpdate(userId);
    }

    public boolean claimQueued(
            AgentExecutionEntity execution,
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

    public AgentExecutionEntity lockNextExpired(Instant now) {
        return baseMapper.selectExpiredForUpdate(now);
    }

    public boolean takeoverExpired(
            AgentExecutionEntity execution,
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
            AgentExecutionEntity execution, long activeExecutionMs, Instant now) {
        return baseMapper.failExpiredBudget(
                        execution.getExecutionId(),
                        execution.getVersion(),
                        execution.getOwnerInstanceId(),
                        execution.getExecutionAttemptNumber(),
                        activeExecutionMs,
                        now)
                == 1;
    }

    public AgentExecutionEntity findRequest(
            String conversationSessionId, String requestType, String idempotencyKey) {
        return lambdaQuery()
                .eq(AgentExecutionEntity::getConversationSessionId, conversationSessionId)
                .eq(AgentExecutionEntity::getRequestType, requestType)
                .eq(AgentExecutionEntity::getRequestIdempotencyKey, idempotencyKey)
                .one();
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
            AgentExecutionEntity execution, long activeExecutionMs, Instant now) {
        return baseMapper.failExpiredRecovery(
                        execution.getExecutionId(),
                        execution.getVersion(),
                        execution.getOwnerInstanceId(),
                        execution.getExecutionAttemptNumber(),
                        activeExecutionMs,
                        now)
                == 1;
    }
}
