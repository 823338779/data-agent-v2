package com.datagent.agentv2.conversation.execution;

import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_AGENT_EXECUTION_NOT_FOUND;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_CONVERSATION_SESSION_NOT_FOUND;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_IDEMPOTENCY_KEY_BLANK;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_ONLY_TERMINAL_EXECUTIONS_RETRY;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_SESSION_CHANGED_CONCURRENTLY;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ID_PREFIX_CONVERSATION_MESSAGE;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ID_PREFIX_EXECUTION;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ID_PREFIX_EXECUTION_EVENT;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.AgentExecutionEvent;
import com.datagent.agentv2.conversation.domain.AgentExecutionStreamCheckpoint;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.ConversationSession;
import com.datagent.agentv2.conversation.enums.ExecutionEventType;
import com.datagent.agentv2.conversation.enums.ExecutionPhase;
import com.datagent.agentv2.conversation.enums.ExecutionRequestType;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import com.datagent.agentv2.conversation.exception.IdempotencyKeyReusedException;
import com.datagent.agentv2.conversation.process.AgentExecutionProcessTerminationService;
import com.datagent.agentv2.conversation.runtime.LocalAgentExecution;
import com.datagent.agentv2.conversation.runtime.LocalExecutionCancelled;
import com.datagent.agentv2.conversation.runtime.LocalExecutionProperties;
import com.datagent.agentv2.conversation.runtime.LocalExecutionStarted;
import com.datagent.agentv2.persistence.service.AgentExecutionEventService;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.AgentExecutionStreamCheckpointService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.platform.context.TenantContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Instant;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AgentExecutionControlService {

    private static final Set<String> TERMINAL = Set.of("done", "cancelled", "failed");

    private final ConversationSessionService sessions;
    private final ConversationMessageService messages;
    private final AgentExecutionService executions;
    private final AgentExecutionEventService events;
    private final AgentExecutionStreamCheckpointService streamCheckpoints;
    private final AgentExecutionProcessTerminationService termination;
    private final LocalExecutionProperties properties;
    private final ApplicationEventPublisher applicationEvents;
    private final IdGenerator ids;

    @Transactional
    public AgentExecution cancel(String sessionId, String executionId) {
        AgentExecution execution = requiredLockedExecution(sessionId, executionId);
        if (TERMINAL.contains(execution.getStatus())) {
            return execution;
        }
        Instant now = Instant.now();
        termination.cancelLocked(execution, now);
        execution.setStatus(ExecutionStatus.CANCELLED.value());
        events.save(
                AgentExecutionEvent.occurred(
                        ids.next(ID_PREFIX_EXECUTION_EVENT),
                        sessionId,
                        executionId,
                        sessions.nextExecutionEventSequence(sessionId),
                        ExecutionEventType.CANCELLED.value(),
                        now));
        applicationEvents.publishEvent(new LocalExecutionCancelled(executionId));
        return execution;
    }

    public void cancelForSteeringLocked(AgentExecution execution, Instant now) {
        termination.cancelForSteeringLocked(execution, now);
    }

    @Transactional
    public AgentExecution retry(String sessionId, String executionId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_IDEMPOTENCY_KEY_BLANK);
        }
        ConversationSession session = sessions.lockById(sessionId);
        if (session == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_CONVERSATION_SESSION_NOT_FOUND);
        }
        String payloadHash = AgentExecutionSubmissionService.payloadHash(executionId);
        AgentExecution replay =
                executions.findRequest(
                        sessionId, ExecutionRequestType.EXECUTION_RETRY.value(), idempotencyKey);
        if (replay != null) {
            if (!payloadHash.equals(replay.getRequestPayloadHash())) {
                throw new IdempotencyKeyReusedException();
            }
            return replay;
        }
        AgentExecution original = requiredLockedExecution(sessionId, executionId);
        if (!TERMINAL.contains(original.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, ERROR_ONLY_TERMINAL_EXECUTIONS_RETRY);
        }
        ConversationSession.AllocatedSequences sequences = session.allocateSubmissionSequences(1);
        if (!sessions.updateById(session)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, ERROR_SESSION_CHANGED_CONCURRENTLY);
        }
        Instant now = Instant.now();
        AgentExecution retry =
                AgentExecution.retry(
                        ids.next(ID_PREFIX_EXECUTION),
                        original,
                        ExecutionRequestType.EXECUTION_RETRY.value(),
                        idempotencyKey,
                        payloadHash,
                        now.plus(properties.deadline()));
        executions.save(retry);
        messages.save(
                ConversationMessage.assistantPlaceholder(
                        ids.next(ID_PREFIX_CONVERSATION_MESSAGE),
                        sessionId,
                        retry.getExecutionId(),
                        sequences.lastConversationMessage()));
        events.save(
                AgentExecutionEvent.occurred(
                        ids.next(ID_PREFIX_EXECUTION_EVENT),
                        sessionId,
                        retry.getExecutionId(),
                        sequences.executionEvent(),
                        ExecutionEventType.STARTED.value(),
                        now));
        streamCheckpoints.save(
                AgentExecutionStreamCheckpoint.initial(
                        retry.getExecutionId(), ExecutionPhase.PREPARING_CONTEXT.value()));
        applicationEvents.publishEvent(
                new LocalExecutionStarted(
                        new LocalAgentExecution(
                                TenantContext.requiredTenantId(),
                                sessionId,
                                retry.getExecutionId(),
                                sequences.lastConversationMessage())));
        return retry;
    }

    private AgentExecution requiredLockedExecution(String sessionId, String executionId) {
        AgentExecution execution = executions.lockById(executionId);
        if (execution == null || !sessionId.equals(execution.getConversationSessionId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_AGENT_EXECUTION_NOT_FOUND);
        }
        return execution;
    }
}
