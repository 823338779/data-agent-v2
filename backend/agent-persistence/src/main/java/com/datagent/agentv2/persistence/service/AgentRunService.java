package com.datagent.agentv2.persistence.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.AgentRunEntity;
import com.datagent.agentv2.persistence.service.mapper.AgentRunMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AgentRunService extends ServiceImpl<AgentRunMapper, AgentRunEntity> {

    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_WAITING_FOR_USER = "waiting_for_user";
    private static final String STATUS_WAITING_CONFIRMATION = "waiting_confirmation";

    public AgentRunEntity lockById(String runId) {
        return baseMapper.selectByIdForUpdate(runId);
    }

    public List<String> queuedOwnerUserIds(int limit) {
        return baseMapper.selectQueuedOwnerUserIds(limit);
    }

    public int countRunningForUser(String userId) {
        return baseMapper.countRunningByOwnerUserId(userId);
    }

    public AgentRunEntity lockOldestQueuedForUser(String userId) {
        return baseMapper.selectOldestQueuedForUpdate(userId);
    }

    public boolean claimQueued(
            AgentRunEntity run,
            long executionAttempt,
            String ownerInstanceId,
            Instant now,
            Instant leaseExpiresAt,
            Instant hardDeadlineAt) {
        return baseMapper.claimQueued(
                        run.getRunId(),
                        run.getVersion(),
                        executionAttempt,
                        ownerInstanceId,
                        now,
                        leaseExpiresAt,
                        hardDeadlineAt)
                == 1;
    }

    public boolean renewLease(
            String runId,
            String ownerInstanceId,
            long executionAttempt,
            Instant now,
            Instant leaseExpiresAt) {
        return baseMapper.renewLease(runId, ownerInstanceId, executionAttempt, now, leaseExpiresAt)
                == 1;
    }

    public boolean fenceOwned(
            String runId, String ownerInstanceId, long executionAttempt, Instant now) {
        return baseMapper.fenceOwned(runId, ownerInstanceId, executionAttempt, now) == 1;
    }

    public boolean cancellationRequested(
            String runId, String ownerInstanceId, long executionAttempt) {
        return baseMapper.countCancellationRequested(runId, ownerInstanceId, executionAttempt) == 1;
    }

    public boolean cancelOwnedIfRequested(
            String runId, String ownerInstanceId, long executionAttempt, Instant now) {
        return baseMapper.cancelOwnedIfRequested(runId, ownerInstanceId, executionAttempt, now)
                == 1;
    }

    public boolean failOwnedPastDeadline(
            String runId, String ownerInstanceId, long executionAttempt, Instant now) {
        return baseMapper.failOwnedPastDeadline(runId, ownerInstanceId, executionAttempt, now) == 1;
    }

    public AgentRunEntity lockNextExpired(Instant now) {
        return baseMapper.selectExpiredForUpdate(now);
    }

    public boolean takeoverExpired(
            AgentRunEntity run,
            String newOwnerInstanceId,
            long newExecutionAttempt,
            long activeExecutionMs,
            Instant now,
            Instant leaseExpiresAt,
            Instant hardDeadlineAt) {
        return baseMapper.takeoverExpired(
                        run.getRunId(),
                        run.getVersion(),
                        run.getOwnerInstanceId(),
                        run.getExecutionAttempt(),
                        newOwnerInstanceId,
                        newExecutionAttempt,
                        activeExecutionMs,
                        now,
                        leaseExpiresAt,
                        hardDeadlineAt)
                == 1;
    }

    public boolean failExpiredBudget(AgentRunEntity run, long activeExecutionMs, Instant now) {
        return baseMapper.failExpiredBudget(
                        run.getRunId(),
                        run.getVersion(),
                        run.getOwnerInstanceId(),
                        run.getExecutionAttempt(),
                        activeExecutionMs,
                        now)
                == 1;
    }

    public AgentRunEntity findRequest(String sessionId, String requestType, String idempotencyKey) {
        return lambdaQuery()
                .eq(AgentRunEntity::getSessionId, sessionId)
                .eq(AgentRunEntity::getRequestType, requestType)
                .eq(AgentRunEntity::getRequestIdempotencyKey, idempotencyKey)
                .one();
    }

    public boolean hasActiveRun(String sessionId) {
        return lambdaQuery()
                        .eq(AgentRunEntity::getSessionId, sessionId)
                        .in(
                                AgentRunEntity::getStatus,
                                STATUS_QUEUED,
                                STATUS_RUNNING,
                                STATUS_WAITING_FOR_USER,
                                STATUS_WAITING_CONFIRMATION)
                        .count()
                > 0;
    }
}
