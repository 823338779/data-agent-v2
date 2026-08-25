package com.datagent.agentv2.conversation.execution;

import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_CONVERSATION_SESSION_NOT_FOUND;
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
import com.datagent.agentv2.conversation.exception.ActiveExecutionConflictException;
import com.datagent.agentv2.conversation.exception.IdempotencyKeyReusedException;
import com.datagent.agentv2.conversation.runtime.LocalAgentExecution;
import com.datagent.agentv2.conversation.runtime.LocalExecutionProperties;
import com.datagent.agentv2.conversation.runtime.LocalExecutionStarted;
import com.datagent.agentv2.persistence.service.AgentExecutionEventService;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.AgentExecutionStreamCheckpointService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.platform.context.OperatorContext;
import com.datagent.agentv2.platform.context.TenantContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 在一个事务中创建立即运行的本机 execution。 */
@Service
@RequiredArgsConstructor
public class AgentExecutionSubmissionTransactionService {

    private static final ExecutionRequestType REQUEST_TYPE =
            ExecutionRequestType.MESSAGE_SUBMISSION;

    private final ConversationSessionService sessions;
    private final ConversationMessageService messages;
    private final AgentExecutionService executions;
    private final AgentExecutionEventService events;
    private final AgentExecutionStreamCheckpointService streamCheckpoints;
    private final LocalExecutionProperties properties;
    private final ApplicationEventPublisher applicationEvents;
    private final IdGenerator ids;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConversationSession createOrReplay(
            String sessionId,
            String content,
            String idempotencyKey,
            String payloadHash) {
        ConversationSession session = sessions.lockById(sessionId);
        if (session == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_CONVERSATION_SESSION_NOT_FOUND);
        }
        AgentExecution replay =
                executions.findRequest(sessionId, REQUEST_TYPE.value(), idempotencyKey);
        if (replay != null) {
            requireSamePayload(replay, payloadHash);
            return session;
        }
        AgentExecution active = executions.lockActiveByConversationSessionId(sessionId);
        if (active != null) {
            throw new ActiveExecutionConflictException();
        }

        ConversationSession.AllocatedSequences sequences = session.allocateSubmissionSequences(2);
        if (!sessions.updateById(session)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, ERROR_SESSION_CHANGED_CONCURRENTLY);
        }

        String userId = OperatorContext.requiredUserId();
        ConversationMessage userMessage =
                ConversationMessage.user(
                        ids.next(ID_PREFIX_CONVERSATION_MESSAGE),
                        sessionId,
                        content,
                        sequences.firstConversationMessage(),
                        userId);
        messages.save(userMessage);

        Instant now = Instant.now();
        AgentExecution execution =
                AgentExecution.running(
                        ids.next(ID_PREFIX_EXECUTION),
                        sessionId,
                        userMessage.getConversationMessageId(),
                        REQUEST_TYPE.value(),
                        idempotencyKey,
                        payloadHash,
                        now.plus(properties.deadline()));
        executions.save(execution);
        userMessage.associateExecution(execution.getExecutionId());
        messages.updateById(userMessage);
        messages.save(
                ConversationMessage.assistantPlaceholder(
                        ids.next(ID_PREFIX_CONVERSATION_MESSAGE),
                        sessionId,
                        execution.getExecutionId(),
                        sequences.lastConversationMessage()));
        events.save(
                AgentExecutionEvent.occurred(
                        ids.next(ID_PREFIX_EXECUTION_EVENT),
                        sessionId,
                        execution.getExecutionId(),
                        sequences.executionEvent(),
                        ExecutionEventType.STARTED.value(),
                        now));
        streamCheckpoints.save(
                AgentExecutionStreamCheckpoint.initial(
                        execution.getExecutionId(), ExecutionPhase.PREPARING_CONTEXT.value()));
        applicationEvents.publishEvent(
                new LocalExecutionStarted(
                        new LocalAgentExecution(
                                TenantContext.requiredTenantId(),
                                sessionId,
                                execution.getExecutionId(),
                                sequences.lastConversationMessage())));
        return session;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ConversationSession replayAfterConstraintConflict(
            String sessionId, String idempotencyKey, String payloadHash) {
        AgentExecution replay =
                executions.findRequest(sessionId, REQUEST_TYPE.value(), idempotencyKey);
        if (replay == null) {
            return null;
        }
        requireSamePayload(replay, payloadHash);
        ConversationSession session = sessions.findByUuid(sessionId);
        if (session == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_CONVERSATION_SESSION_NOT_FOUND);
        }
        return session;
    }

    private static void requireSamePayload(AgentExecution execution, String payloadHash) {
        if (!payloadHash.equals(execution.getRequestPayloadHash())) {
            throw new IdempotencyKeyReusedException();
        }
    }
}
