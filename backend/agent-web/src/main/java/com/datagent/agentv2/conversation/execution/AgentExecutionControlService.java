package com.datagent.agentv2.conversation.execution;

import static com.datagent.agentv2.conversation.execution.ExecutionConstants.EMPTY_TEXT;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_AGENT_EXECUTION_NOT_FOUND;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_CONVERSATION_SESSION_NOT_FOUND;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_EXECUTION_CANNOT_BE_CANCELLED;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_IDEMPOTENCY_KEY_BLANK;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_ONLY_TERMINAL_EXECUTIONS_RETRY;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_SESSION_CHANGED_CONCURRENTLY;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ID_PREFIX_CONVERSATION_MESSAGE;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ID_PREFIX_EXECUTION;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ID_PREFIX_EXECUTION_EVENT;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ID_PREFIX_EXECUTION_STREAM_CHECKPOINT;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ID_PREFIX_NOTIFICATION_MESSAGE;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.REQUEST_ID_UNKNOWN;

import com.datagent.agentv2.conversation.cluster.AgentExecutionCancellationSignal;
import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.AgentExecutionEvent;
import com.datagent.agentv2.conversation.domain.AgentExecutionStreamCheckpoint;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.ConversationSession;
import com.datagent.agentv2.conversation.enums.ExecutionAggregateType;
import com.datagent.agentv2.conversation.enums.ExecutionControlType;
import com.datagent.agentv2.conversation.enums.ExecutionEventType;
import com.datagent.agentv2.conversation.enums.ExecutionNotificationType;
import com.datagent.agentv2.conversation.enums.ExecutionPhase;
import com.datagent.agentv2.conversation.enums.ExecutionRequestType;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import com.datagent.agentv2.conversation.enums.MessageRole;
import com.datagent.agentv2.conversation.exception.IdempotencyKeyReusedException;
import com.datagent.agentv2.conversation.process.AgentExecutionProcessTerminationService;
import com.datagent.agentv2.persistence.entity.NotificationOutboxMessageEntity;
import com.datagent.agentv2.persistence.service.AgentExecutionAttemptService;
import com.datagent.agentv2.persistence.service.AgentExecutionEventService;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.AgentExecutionStreamCheckpointService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.persistence.service.NotificationOutboxMessageService;
import com.datagent.agentv2.platform.context.TenantContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Instant;
import java.util.Set;
import org.springframework.http.HttpStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class AgentExecutionControlService {

    private static final Set<String> DIRECT_CANCELLATION_STATUSES =
            Set.of(
                    ExecutionStatus.QUEUED.value(),
                    ExecutionStatus.WAITING_FOR_USER.value(),
                    ExecutionStatus.WAITING_CONFIRMATION.value());
    private static final Set<String> TERMINAL_STATUSES =
            Set.of(
                    ExecutionStatus.DONE.value(),
                    ExecutionStatus.CANCELLED.value(),
                    ExecutionStatus.FAILED.value());

    private final ConversationSessionService sessions;
    private final ConversationMessageService messages;
    private final AgentExecutionService agentExecutions;
    private final AgentExecutionEventService events;
    private final AgentExecutionAttemptService attempts;
    private final AgentExecutionStreamCheckpointService streamCheckpoints;
    private final NotificationOutboxMessageService outbox;
    private final AgentExecutionProcessTerminationService termination;
    private final IdGenerator ids;
    private final ObjectMapper json;

    @Transactional
    public AgentExecution cancel(String conversationSessionId, String executionId) {
        return cancelLocked(conversationSessionId, executionId);
    }

    @Transactional
    public AgentExecution retry(
            String conversationSessionId, String executionId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_IDEMPOTENCY_KEY_BLANK);
        }
        return retryLocked(conversationSessionId, executionId, idempotencyKey);
    }

    private AgentExecution cancelLocked(String conversationSessionId, String executionId) {
        AgentExecution run = requiredLockedExecution(conversationSessionId, executionId);
        if (TERMINAL_STATUSES.contains(run.getStatus())) {
            return run;
        }
        Instant now = Instant.now();
        if (DIRECT_CANCELLATION_STATUSES.contains(run.getStatus())) {
            termination.cancelLocked(run, now);
            return run;
        }
        if (!ExecutionStatus.RUNNING.value().equals(run.getStatus())) {
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
                        conversationSessionId,
                        ExecutionRequestType.EXECUTION_RETRY.value(),
                        idempotencyKey);
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
        ConversationSession.AllocatedSequences sequences = session.allocateSubmissionSequences(1);
        if (!sessions.updateById(session)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, ERROR_SESSION_CHANGED_CONCURRENTLY);
        }
        AgentExecution retry =
                AgentExecution.retry(
                        ids.next(ID_PREFIX_EXECUTION),
                        original,
                        REQUEST_ID_UNKNOWN,
                        now,
                        ExecutionRequestType.EXECUTION_RETRY.value(),
                        idempotencyKey,
                        payloadHash,
                        ExecutionStatus.QUEUED.value(),
                        ExecutionPhase.QUEUED.value());
        agentExecutions.save(retry);

        // retry 只复用原 user 消息，并为新 execution 创建一条新的 assistant 中心占位行。
        ConversationMessage placeholder =
                ConversationMessage.assistantPlaceholder(
                        ids.next(ID_PREFIX_CONVERSATION_MESSAGE),
                        conversationSessionId,
                        retry.getExecutionId(),
                        sequences.lastConversationMessage(),
                        MessageRole.ASSISTANT.value());
        messages.save(placeholder);

        events.save(
                AgentExecutionEvent.occurred(
                        ids.next(ID_PREFIX_EXECUTION_EVENT),
                        conversationSessionId,
                        retry.getExecutionId(),
                        sequences.executionEvent(),
                        ExecutionEventType.QUEUED.value(),
                        null,
                        now));

        streamCheckpoints.save(
                AgentExecutionStreamCheckpoint.initial(
                        ids.next(ID_PREFIX_EXECUTION_STREAM_CHECKPOINT),
                        retry.getExecutionId(),
                        ExecutionPhase.QUEUED.value(),
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
                        ExecutionControlType.CANCEL);
        NotificationOutboxMessageEntity notification =
                NotificationOutboxMessageEntity.compose(
                        notificationMessageId,
                        ExecutionNotificationType.CANCELLATION_REQUESTED.value(),
                        ExecutionAggregateType.AGENT_EXECUTION.value(),
                        run.getExecutionId(),
                        run.getVersion(),
                        json.writeValueAsString(payload),
                        requestedAt);
        outbox.insertControl(notification);
    }
}
