package com.datagent.agentv2.conversation.application;

import static com.datagent.agentv2.conversation.RunConstants.AGGREGATE_RUN;
import static com.datagent.agentv2.conversation.RunConstants.CONTROL_CANCEL;
import static com.datagent.agentv2.conversation.RunConstants.EMPTY_TEXT;
import static com.datagent.agentv2.conversation.RunConstants.ERROR_AGENT_RUN_NOT_FOUND;
import static com.datagent.agentv2.conversation.RunConstants.ERROR_AGENT_SESSION_NOT_FOUND;
import static com.datagent.agentv2.conversation.RunConstants.ERROR_IDEMPOTENCY_KEY_BLANK;
import static com.datagent.agentv2.conversation.RunConstants.ERROR_ONLY_TERMINAL_RUNS_RETRY;
import static com.datagent.agentv2.conversation.RunConstants.ERROR_RUN_CANNOT_BE_CANCELLED;
import static com.datagent.agentv2.conversation.RunConstants.EVENT_RUN_QUEUED;
import static com.datagent.agentv2.conversation.RunConstants.ID_PREFIX_EVENT;
import static com.datagent.agentv2.conversation.RunConstants.ID_PREFIX_NOTIFICATION;
import static com.datagent.agentv2.conversation.RunConstants.ID_PREFIX_RUN;
import static com.datagent.agentv2.conversation.RunConstants.ID_PREFIX_STREAM;
import static com.datagent.agentv2.conversation.RunConstants.NOTIFICATION_RUN_CONTROL_REQUESTED;
import static com.datagent.agentv2.conversation.RunConstants.PAYLOAD_CONTROL_VERSION;
import static com.datagent.agentv2.conversation.RunConstants.PAYLOAD_EXECUTION_ATTEMPT;
import static com.datagent.agentv2.conversation.RunConstants.PAYLOAD_NOTIFICATION_ID;
import static com.datagent.agentv2.conversation.RunConstants.PAYLOAD_OWNER_INSTANCE_ID;
import static com.datagent.agentv2.conversation.RunConstants.PAYLOAD_REQUESTED_AT;
import static com.datagent.agentv2.conversation.RunConstants.PAYLOAD_RUN_ID;
import static com.datagent.agentv2.conversation.RunConstants.PAYLOAD_TENANT_ID;
import static com.datagent.agentv2.conversation.RunConstants.PAYLOAD_TYPE;
import static com.datagent.agentv2.conversation.RunConstants.PHASE_QUEUED;
import static com.datagent.agentv2.conversation.RunConstants.REQUEST_ID_UNKNOWN;
import static com.datagent.agentv2.conversation.RunConstants.REQUEST_RUN_RETRY;
import static com.datagent.agentv2.conversation.RunConstants.STATUS_CANCELLED;
import static com.datagent.agentv2.conversation.RunConstants.STATUS_DONE;
import static com.datagent.agentv2.conversation.RunConstants.STATUS_FAILED;
import static com.datagent.agentv2.conversation.RunConstants.STATUS_QUEUED;
import static com.datagent.agentv2.conversation.RunConstants.STATUS_RUNNING;
import static com.datagent.agentv2.conversation.RunConstants.STATUS_WAITING_CONFIRMATION;
import static com.datagent.agentv2.conversation.RunConstants.STATUS_WAITING_FOR_USER;

import com.datagent.agentv2.persistence.entity.AgentRunEntity;
import com.datagent.agentv2.persistence.entity.AgentRunEventEntity;
import com.datagent.agentv2.persistence.entity.AgentRunStreamEntity;
import com.datagent.agentv2.persistence.entity.AgentSessionEntity;
import com.datagent.agentv2.persistence.entity.NotificationOutboxEntity;
import com.datagent.agentv2.persistence.service.AgentRunAttemptService;
import com.datagent.agentv2.persistence.service.AgentRunEventService;
import com.datagent.agentv2.persistence.service.AgentRunService;
import com.datagent.agentv2.persistence.service.AgentRunStreamService;
import com.datagent.agentv2.persistence.service.AgentSessionService;
import com.datagent.agentv2.persistence.service.NotificationOutboxService;
import com.datagent.agentv2.platform.context.TenantContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public final class RunControlService {

    private static final Set<String> DIRECT_CANCELLATION_STATUSES =
            Set.of(STATUS_QUEUED, STATUS_WAITING_FOR_USER, STATUS_WAITING_CONFIRMATION);
    private static final Set<String> TERMINAL_STATUSES =
            Set.of(STATUS_DONE, STATUS_CANCELLED, STATUS_FAILED);

    private final AgentSessionService sessions;
    private final AgentRunService runs;
    private final AgentRunEventService events;
    private final AgentRunAttemptService attempts;
    private final AgentRunStreamService streams;
    private final NotificationOutboxService outbox;
    private final IdGenerator ids;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public RunControlService(
            AgentSessionService sessions,
            AgentRunService runs,
            AgentRunEventService events,
            AgentRunAttemptService attempts,
            AgentRunStreamService streams,
            NotificationOutboxService outbox,
            IdGenerator ids,
            ObjectMapper json,
            PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.runs = runs;
        this.events = events;
        this.attempts = attempts;
        this.streams = streams;
        this.outbox = outbox;
        this.ids = ids;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public AgentRunEntity cancel(String sessionId, String runId) {
        return Objects.requireNonNull(
                transactions.execute(ignored -> cancelLocked(sessionId, runId)));
    }

    public AgentRunEntity retry(String sessionId, String runId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_IDEMPOTENCY_KEY_BLANK);
        }
        return Objects.requireNonNull(
                transactions.execute(ignored -> retryLocked(sessionId, runId, idempotencyKey)));
    }

    private AgentRunEntity cancelLocked(String sessionId, String runId) {
        AgentRunEntity run = requiredLockedRun(sessionId, runId);
        if (TERMINAL_STATUSES.contains(run.getStatus())) {
            return run;
        }
        Instant now = Instant.now();
        if (DIRECT_CANCELLATION_STATUSES.contains(run.getStatus())) {
            run.setStatus(STATUS_CANCELLED);
            run.setCompletedAt(now);
            run.setOwnerInstanceId(null);
            run.setActiveSegmentStartedAt(null);
            run.setHeartbeatAt(null);
            run.setLeaseExpiresAt(null);
            run.setHardDeadlineAt(null);
            runs.updateById(run);
            if (run.getExecutionAttempt() > 0) {
                attempts.finish(
                        run.getRunId(), run.getExecutionAttempt(), STATUS_CANCELLED, now, null);
            }
            return run;
        }
        if (!STATUS_RUNNING.equals(run.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_RUN_CANNOT_BE_CANCELLED);
        }
        if (run.getCancellationRequestedAt() != null) {
            return run;
        }
        run.setCancellationRequestedAt(now);
        runs.updateById(run);
        writeControlOutbox(run, now);
        return run;
    }

    private AgentRunEntity retryLocked(String sessionId, String runId, String idempotencyKey) {
        AgentSessionEntity session = sessions.lockById(sessionId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ERROR_AGENT_SESSION_NOT_FOUND);
        }
        String payloadHash = RunSubmissionService.payloadHash(runId);
        AgentRunEntity replay = runs.findRequest(sessionId, REQUEST_RUN_RETRY, idempotencyKey);
        if (replay != null) {
            if (!payloadHash.equals(replay.getRequestPayloadHash())) {
                throw new IdempotencyKeyReusedException();
            }
            return replay;
        }
        AgentRunEntity original = runs.lockById(runId);
        if (original == null || !sessionId.equals(original.getSessionId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ERROR_AGENT_RUN_NOT_FOUND);
        }
        if (!TERMINAL_STATUSES.contains(original.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_ONLY_TERMINAL_RUNS_RETRY);
        }

        Instant now = Instant.now();
        AgentRunEntity retry = new AgentRunEntity();
        retry.setRunId(ids.next(ID_PREFIX_RUN));
        retry.setSessionId(sessionId);
        retry.setTriggeringMessageId(original.getTriggeringMessageId());
        retry.setStatus(STATUS_QUEUED);
        retry.setPhase(PHASE_QUEUED);
        retry.setExecutionAttempt(0L);
        retry.setOwnerUserId(original.getOwnerUserId());
        retry.setRequestId(REQUEST_ID_UNKNOWN);
        retry.setQueuedAt(now);
        retry.setRetryOfRunId(original.getRunId());
        retry.setRecoveryAttemptCount(0);
        retry.setRequestType(REQUEST_RUN_RETRY);
        retry.setRequestIdempotencyKey(idempotencyKey);
        retry.setRequestPayloadHash(payloadHash);
        retry.setExecutionBudgetMs(original.getExecutionBudgetMs());
        retry.setActiveExecutionMs(0L);
        retry.setVersion(0L);
        runs.save(retry);

        AgentRunEventEntity event = new AgentRunEventEntity();
        event.setEventId(ids.next(ID_PREFIX_EVENT));
        event.setSessionId(sessionId);
        event.setRunId(retry.getRunId());
        event.setSequence(sessions.nextEventSequence(sessionId));
        event.setType(EVENT_RUN_QUEUED);
        event.setOccurredAt(now);
        event.setVersion(0L);
        events.save(event);

        AgentRunStreamEntity stream = new AgentRunStreamEntity();
        stream.setStreamId(ids.next(ID_PREFIX_STREAM));
        stream.setRunId(retry.getRunId());
        stream.setExecutionAttempt(0L);
        stream.setModelTurnSequence(0L);
        stream.setPhase(PHASE_QUEUED);
        stream.setStreamSequence(0L);
        stream.setReasoning(EMPTY_TEXT);
        stream.setAnswer(EMPTY_TEXT);
        stream.setVersion(0L);
        streams.save(stream);
        return retry;
    }

    private AgentRunEntity requiredLockedRun(String sessionId, String runId) {
        AgentRunEntity run = runs.lockById(runId);
        if (run == null || !sessionId.equals(run.getSessionId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ERROR_AGENT_RUN_NOT_FOUND);
        }
        return run;
    }

    private void writeControlOutbox(AgentRunEntity run, Instant requestedAt) {
        String notificationId = ids.next(ID_PREFIX_NOTIFICATION);
        Map<String, Object> payload =
                Map.of(
                        PAYLOAD_NOTIFICATION_ID, notificationId,
                        PAYLOAD_TENANT_ID, TenantContext.requiredTenantId(),
                        PAYLOAD_RUN_ID, run.getRunId(),
                        PAYLOAD_OWNER_INSTANCE_ID, run.getOwnerInstanceId(),
                        PAYLOAD_EXECUTION_ATTEMPT, run.getExecutionAttempt(),
                        PAYLOAD_CONTROL_VERSION, run.getVersion(),
                        PAYLOAD_REQUESTED_AT, requestedAt.toString(),
                        PAYLOAD_TYPE, CONTROL_CANCEL);
        NotificationOutboxEntity notification = new NotificationOutboxEntity();
        notification.setNotificationType(NOTIFICATION_RUN_CONTROL_REQUESTED);
        notification.setAggregateType(AGGREGATE_RUN);
        notification.setNotificationId(notificationId);
        notification.setAggregateId(run.getRunId());
        notification.setAggregateVersion(run.getVersion());
        notification.setPayload(json.writeValueAsString(payload));
        notification.setNextAttemptAt(requestedAt);
        notification.setVersion(0L);
        outbox.insertControl(notification);
    }
}
