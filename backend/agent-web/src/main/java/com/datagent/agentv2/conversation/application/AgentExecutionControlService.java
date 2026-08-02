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

import com.datagent.agentv2.conversation.cluster.AgentExecutionCancellationSignal;
import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.AgentExecutionEvent;
import com.datagent.agentv2.conversation.domain.AgentExecutionStreamCheckpoint;
import com.datagent.agentv2.conversation.domain.ConversationSession;
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

    public AgentExecution cancel(String conversationSessionId, String executionId) {
        return Objects.requireNonNull(
                transactions.execute(ignored -> cancelLocked(conversationSessionId, executionId)));
    }

    public AgentExecution retry(
            String conversationSessionId, String executionId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_IDEMPOTENCY_KEY_BLANK);
        }
        return Objects.requireNonNull(
                transactions.execute(
                        ignored ->
                                retryLocked(conversationSessionId, executionId, idempotencyKey)));
    }

    private AgentExecution cancelLocked(String conversationSessionId, String executionId) {
        AgentExecution run = requiredLockedExecution(conversationSessionId, executionId);
        if (TERMINAL_STATUSES.contains(run.getStatus())) {
            return run;
        }
        Instant now = Instant.now();
        if (DIRECT_CANCELLATION_STATUSES.contains(run.getStatus())) {
            run.cancelDirectly(STATUS_CANCELLED, now);
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
        run.requestCancellation(now);
        agentExecutions.updateById(run);
        writeControlOutbox(run, now);
        return run;
    }

    private AgentExecution retryLocked(
            String conversationSessionId, String executionId, String idempotencyKey) {
        ConversationSession session = sessions.lockById(conversationSessionId);
        if (session == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_CONVERSATION_SESSION_NOT_FOUND);
        }
        String payloadHash = AgentExecutionSubmissionService.payloadHash(executionId);
        AgentExecution replay =
                agentExecutions.findRequest(
                        conversationSessionId, REQUEST_EXECUTION_RETRY, idempotencyKey);
        if (replay != null) {
            if (!payloadHash.equals(replay.getRequestPayloadHash())) {
                throw new IdempotencyKeyReusedException();
            }
            return replay;
        }
        AgentExecution original = agentExecutions.lockById(executionId);
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
        AgentExecution retry =
                AgentExecution.retry(
                        ids.next(ID_PREFIX_EXECUTION),
                        original,
                        REQUEST_ID_UNKNOWN,
                        now,
                        REQUEST_EXECUTION_RETRY,
                        idempotencyKey,
                        payloadHash,
                        STATUS_QUEUED,
                        PHASE_QUEUED);
        agentExecutions.save(retry);

        events.save(
                AgentExecutionEvent.occurred(
                        ids.next(ID_PREFIX_EXECUTION_EVENT),
                        conversationSessionId,
                        retry.getExecutionId(),
                        sessions.nextExecutionEventSequence(conversationSessionId),
                        EVENT_EXECUTION_QUEUED,
                        null,
                        now));

        streamCheckpoints.save(
                AgentExecutionStreamCheckpoint.initial(
                        ids.next(ID_PREFIX_EXECUTION_STREAM_CHECKPOINT),
                        retry.getExecutionId(),
                        PHASE_QUEUED,
                        EMPTY_TEXT,
                        EMPTY_TEXT));
        return retry;
    }

    private AgentExecution requiredLockedExecution(
            String conversationSessionId, String executionId) {
        AgentExecution run = agentExecutions.lockById(executionId);
        if (run == null || !conversationSessionId.equals(run.getConversationSessionId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_AGENT_EXECUTION_NOT_FOUND);
        }
        return run;
    }

    private void writeControlOutbox(AgentExecution run, Instant requestedAt) {
        String notificationMessageId = ids.next(ID_PREFIX_NOTIFICATION_MESSAGE);
        AgentExecutionCancellationSignal payload =
                new AgentExecutionCancellationSignal(
                        notificationMessageId,
                        TenantContext.requiredTenantId(),
                        run.getExecutionId(),
                        run.getOwnerInstanceId(),
                        run.getExecutionAttemptNumber(),
                        run.getVersion(),
                        requestedAt,
                        CONTROL_CANCEL);
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
