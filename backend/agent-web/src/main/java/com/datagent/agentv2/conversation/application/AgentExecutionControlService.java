package com.datagent.agentv2.conversation.application;

import static com.datagent.agentv2.conversation.AgentExecutionConstants.AGGREGATE_EXECUTION;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.CONTROL_CANCEL;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.EMPTY_TEXT;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.ERROR_AGENT_EXECUTION_NOT_FOUND;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.ERROR_CONVERSATION_SESSION_NOT_FOUND;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.ERROR_EXECUTION_CANNOT_BE_CANCELLED;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.ERROR_IDEMPOTENCY_KEY_BLANK;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.ERROR_ONLY_TERMINAL_EXECUTIONS_RETRY;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.EVENT_EXECUTION_QUEUED;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.ID_PREFIX_EXECUTION;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.ID_PREFIX_EXECUTION_EVENT;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.ID_PREFIX_EXECUTION_STREAM_CHECKPOINT;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.ID_PREFIX_NOTIFICATION_MESSAGE;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.NOTIFICATION_EXECUTION_CANCELLATION_REQUESTED;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.PAYLOAD_CONTROL_VERSION;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.PAYLOAD_EXECUTION_ATTEMPT_NUMBER;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.PAYLOAD_EXECUTION_ID;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.PAYLOAD_NOTIFICATION_MESSAGE_ID;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.PAYLOAD_OWNER_INSTANCE_ID;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.PAYLOAD_REQUESTED_AT;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.PAYLOAD_TENANT_ID;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.PAYLOAD_TYPE;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.PHASE_QUEUED;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.REQUEST_EXECUTION_RETRY;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.REQUEST_ID_UNKNOWN;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.STATUS_CANCELLED;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.STATUS_DONE;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.STATUS_FAILED;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.STATUS_QUEUED;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.STATUS_RUNNING;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.STATUS_WAITING_CONFIRMATION;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.STATUS_WAITING_FOR_USER;

import com.datagent.agentv2.persistence.entity.AgentExecutionEntity;
import com.datagent.agentv2.persistence.entity.AgentExecutionEventEntity;
import com.datagent.agentv2.persistence.entity.AgentExecutionStreamCheckpointEntity;
import com.datagent.agentv2.persistence.entity.ConversationSessionEntity;
import com.datagent.agentv2.persistence.entity.NotificationOutboxMessageEntity;
import com.datagent.agentv2.persistence.service.AgentExecutionAttemptService;
import com.datagent.agentv2.persistence.service.AgentExecutionEventService;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.AgentExecutionStreamCheckpointService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.persistence.service.NotificationOutboxMessageService;
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
public final class AgentExecutionControlService {

    private static final Set<String> DIRECT_CANCELLATION_STATUSES =
            Set.of(STATUS_QUEUED, STATUS_WAITING_FOR_USER, STATUS_WAITING_CONFIRMATION);
    private static final Set<String> TERMINAL_STATUSES =
            Set.of(STATUS_DONE, STATUS_CANCELLED, STATUS_FAILED);

    private final ConversationSessionService sessions;
    private final AgentExecutionService agentExecutions;
    private final AgentExecutionEventService events;
    private final AgentExecutionAttemptService attempts;
    private final AgentExecutionStreamCheckpointService streamCheckpoints;
    private final NotificationOutboxMessageService outbox;
    private final IdGenerator ids;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public AgentExecutionControlService(
            ConversationSessionService sessions,
            AgentExecutionService agentExecutions,
            AgentExecutionEventService events,
            AgentExecutionAttemptService attempts,
            AgentExecutionStreamCheckpointService streamCheckpoints,
            NotificationOutboxMessageService outbox,
            IdGenerator ids,
            ObjectMapper json,
            PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.agentExecutions = agentExecutions;
        this.events = events;
        this.attempts = attempts;
        this.streamCheckpoints = streamCheckpoints;
        this.outbox = outbox;
        this.ids = ids;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public AgentExecutionEntity cancel(String conversationSessionId, String executionId) {
        return Objects.requireNonNull(
                transactions.execute(ignored -> cancelLocked(conversationSessionId, executionId)));
    }

    public AgentExecutionEntity retry(
            String conversationSessionId, String executionId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_IDEMPOTENCY_KEY_BLANK);
        }
        return Objects.requireNonNull(
                transactions.execute(
                        ignored ->
                                retryLocked(conversationSessionId, executionId, idempotencyKey)));
    }

    private AgentExecutionEntity cancelLocked(String conversationSessionId, String executionId) {
        AgentExecutionEntity run = requiredLockedExecution(conversationSessionId, executionId);
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
            agentExecutions.updateById(run);
            if (run.getExecutionAttemptNumber() > 0) {
                attempts.finish(
                        run.getExecutionId(),
                        run.getExecutionAttemptNumber(),
                        STATUS_CANCELLED,
                        now,
                        null);
            }
            return run;
        }
        if (!STATUS_RUNNING.equals(run.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, ERROR_EXECUTION_CANNOT_BE_CANCELLED);
        }
        if (run.getCancellationRequestedAt() != null) {
            return run;
        }
        run.setCancellationRequestedAt(now);
        agentExecutions.updateById(run);
        writeControlOutbox(run, now);
        return run;
    }

    private AgentExecutionEntity retryLocked(
            String conversationSessionId, String executionId, String idempotencyKey) {
        ConversationSessionEntity session = sessions.lockById(conversationSessionId);
        if (session == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_CONVERSATION_SESSION_NOT_FOUND);
        }
        String payloadHash = AgentExecutionSubmissionService.payloadHash(executionId);
        AgentExecutionEntity replay =
                agentExecutions.findRequest(
                        conversationSessionId, REQUEST_EXECUTION_RETRY, idempotencyKey);
        if (replay != null) {
            if (!payloadHash.equals(replay.getRequestPayloadHash())) {
                throw new IdempotencyKeyReusedException();
            }
            return replay;
        }
        AgentExecutionEntity original = agentExecutions.lockById(executionId);
        if (original == null
                || !conversationSessionId.equals(original.getConversationSessionId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_AGENT_EXECUTION_NOT_FOUND);
        }
        if (!TERMINAL_STATUSES.contains(original.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, ERROR_ONLY_TERMINAL_EXECUTIONS_RETRY);
        }

        Instant now = Instant.now();
        AgentExecutionEntity retry = new AgentExecutionEntity();
        retry.setExecutionId(ids.next(ID_PREFIX_EXECUTION));
        retry.setConversationSessionId(conversationSessionId);
        retry.setTriggeringConversationMessageId(original.getTriggeringConversationMessageId());
        retry.setStatus(STATUS_QUEUED);
        retry.setPhase(PHASE_QUEUED);
        retry.setExecutionAttemptNumber(0L);
        retry.setOwnerUserId(original.getOwnerUserId());
        retry.setRequestId(REQUEST_ID_UNKNOWN);
        retry.setQueuedAt(now);
        retry.setRetryOfExecutionId(original.getExecutionId());
        retry.setRecoveryAttemptCount(0);
        retry.setRequestType(REQUEST_EXECUTION_RETRY);
        retry.setRequestIdempotencyKey(idempotencyKey);
        retry.setRequestPayloadHash(payloadHash);
        retry.setExecutionBudgetMs(original.getExecutionBudgetMs());
        retry.setActiveExecutionMs(0L);
        retry.setVersion(0L);
        agentExecutions.save(retry);

        AgentExecutionEventEntity event = new AgentExecutionEventEntity();
        event.setExecutionEventId(ids.next(ID_PREFIX_EXECUTION_EVENT));
        event.setConversationSessionId(conversationSessionId);
        event.setExecutionId(retry.getExecutionId());
        event.setExecutionEventSequence(sessions.nextExecutionEventSequence(conversationSessionId));
        event.setType(EVENT_EXECUTION_QUEUED);
        event.setOccurredAt(now);
        event.setVersion(0L);
        events.save(event);

        AgentExecutionStreamCheckpointEntity stream = new AgentExecutionStreamCheckpointEntity();
        stream.setExecutionStreamCheckpointId(ids.next(ID_PREFIX_EXECUTION_STREAM_CHECKPOINT));
        stream.setExecutionId(retry.getExecutionId());
        stream.setExecutionAttemptNumber(0L);
        stream.setModelInvocationSequence(0L);
        stream.setPhase(PHASE_QUEUED);
        stream.setOutputDeltaSequence(0L);
        stream.setReasoning(EMPTY_TEXT);
        stream.setAnswer(EMPTY_TEXT);
        stream.setVersion(0L);
        streamCheckpoints.save(stream);
        return retry;
    }

    private AgentExecutionEntity requiredLockedExecution(
            String conversationSessionId, String executionId) {
        AgentExecutionEntity run = agentExecutions.lockById(executionId);
        if (run == null || !conversationSessionId.equals(run.getConversationSessionId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_AGENT_EXECUTION_NOT_FOUND);
        }
        return run;
    }

    private void writeControlOutbox(AgentExecutionEntity run, Instant requestedAt) {
        String notificationMessageId = ids.next(ID_PREFIX_NOTIFICATION_MESSAGE);
        Map<String, Object> payload =
                Map.of(
                        PAYLOAD_NOTIFICATION_MESSAGE_ID, notificationMessageId,
                        PAYLOAD_TENANT_ID, TenantContext.requiredTenantId(),
                        PAYLOAD_EXECUTION_ID, run.getExecutionId(),
                        PAYLOAD_OWNER_INSTANCE_ID, run.getOwnerInstanceId(),
                        PAYLOAD_EXECUTION_ATTEMPT_NUMBER, run.getExecutionAttemptNumber(),
                        PAYLOAD_CONTROL_VERSION, run.getVersion(),
                        PAYLOAD_REQUESTED_AT, requestedAt.toString(),
                        PAYLOAD_TYPE, CONTROL_CANCEL);
        NotificationOutboxMessageEntity notification = new NotificationOutboxMessageEntity();
        notification.setNotificationType(NOTIFICATION_EXECUTION_CANCELLATION_REQUESTED);
        notification.setAggregateType(AGGREGATE_EXECUTION);
        notification.setNotificationMessageId(notificationMessageId);
        notification.setAggregateId(run.getExecutionId());
        notification.setAggregateVersion(run.getVersion());
        notification.setPayload(json.writeValueAsString(payload));
        notification.setNextAttemptAt(requestedAt);
        notification.setVersion(0L);
        outbox.insertControl(notification);
    }
}
