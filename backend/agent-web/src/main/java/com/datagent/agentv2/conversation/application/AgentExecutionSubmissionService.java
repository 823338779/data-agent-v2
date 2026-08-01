package com.datagent.agentv2.conversation.application;

import static com.datagent.agentv2.conversation.AgentExecutionConstants.EMPTY_TEXT;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.ERROR_CONVERSATION_SESSION_NOT_FOUND;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.ERROR_HASH_ALGORITHM_UNAVAILABLE_SUFFIX;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.ERROR_IDEMPOTENCY_KEY_BLANK;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.ERROR_SESSION_CHANGED_CONCURRENTLY;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.EVENT_EXECUTION_QUEUED;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.HASH_ALGORITHM_SHA_256;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.ID_PREFIX_CONVERSATION_MESSAGE;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.ID_PREFIX_EXECUTION;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.ID_PREFIX_EXECUTION_EVENT;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.ID_PREFIX_EXECUTION_STREAM_CHECKPOINT;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.LINE_SEPARATOR;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.MESSAGE_ROLE_USER;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.PHASE_QUEUED;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.REQUEST_MESSAGE_SUBMISSION;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.STATUS_QUEUED;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.WINDOWS_LINE_SEPARATOR;

import com.datagent.agentv2.persistence.entity.AgentExecutionEntity;
import com.datagent.agentv2.persistence.entity.AgentExecutionEventEntity;
import com.datagent.agentv2.persistence.entity.AgentExecutionStreamCheckpointEntity;
import com.datagent.agentv2.persistence.entity.ConversationMessageEntity;
import com.datagent.agentv2.persistence.entity.ConversationSessionEntity;
import com.datagent.agentv2.persistence.service.AgentExecutionEventService;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.AgentExecutionStreamCheckpointService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.platform.context.OperatorContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public final class AgentExecutionSubmissionService {

    static final String REQUEST_TYPE = REQUEST_MESSAGE_SUBMISSION;
    static final long DEFAULT_EXECUTION_BUDGET_MS = 600_000L;

    private final ConversationSessionService sessions;
    private final ConversationMessageService messages;
    private final AgentExecutionService agentExecutions;
    private final AgentExecutionEventService events;
    private final AgentExecutionStreamCheckpointService streamCheckpoints;
    private final IdGenerator ids;
    private final TransactionTemplate transactions;

    public AgentExecutionSubmissionService(
            ConversationSessionService sessions,
            ConversationMessageService messages,
            AgentExecutionService agentExecutions,
            AgentExecutionEventService events,
            AgentExecutionStreamCheckpointService streamCheckpoints,
            IdGenerator ids,
            PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.messages = messages;
        this.agentExecutions = agentExecutions;
        this.events = events;
        this.streamCheckpoints = streamCheckpoints;
        this.ids = ids;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ConversationSessionEntity submit(
            String conversationSessionId, String content, String idempotencyKey, String requestId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_IDEMPOTENCY_KEY_BLANK);
        }
        String payloadHash = payloadHash(content);
        try {
            return Objects.requireNonNull(
                    transactions.execute(
                            ignored ->
                                    createOrReplay(
                                            conversationSessionId,
                                            content,
                                            idempotencyKey,
                                            payloadHash,
                                            requestId)));
        } catch (DataIntegrityViolationException conflict) {
            ConversationSessionEntity replay =
                    transactions.execute(
                            ignored ->
                                    replayAfterConstraintConflict(
                                            conversationSessionId, idempotencyKey, payloadHash));
            if (replay != null) {
                return replay;
            }
            throw conflict;
        }
    }

    private ConversationSessionEntity createOrReplay(
            String conversationSessionId,
            String content,
            String idempotencyKey,
            String payloadHash,
            String requestId) {
        ConversationSessionEntity session = sessions.lockById(conversationSessionId);
        if (session == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_CONVERSATION_SESSION_NOT_FOUND);
        }
        AgentExecutionEntity replay =
                agentExecutions.findRequest(conversationSessionId, REQUEST_TYPE, idempotencyKey);
        if (replay != null) {
            requireSamePayload(replay, payloadHash);
            return session;
        }
        if (agentExecutions.hasActiveExecution(conversationSessionId)) {
            throw new ActiveExecutionConflictException();
        }

        long conversationMessageSequence = session.getConversationMessageSequence() + 1;
        long executionEventSequence = session.getExecutionEventSequence() + 1;
        session.setConversationMessageSequence(conversationMessageSequence);
        session.setExecutionEventSequence(executionEventSequence);
        if (!sessions.updateById(session)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, ERROR_SESSION_CHANGED_CONCURRENTLY);
        }

        ConversationMessageEntity message = new ConversationMessageEntity();
        message.setConversationMessageId(ids.next(ID_PREFIX_CONVERSATION_MESSAGE));
        message.setConversationSessionId(conversationSessionId);
        message.setRole(MESSAGE_ROLE_USER);
        message.setContent(content);
        message.setConversationMessageSequence(conversationMessageSequence);
        message.setAuthorUserId(OperatorContext.requiredOperator().userId());
        message.setVersion(0L);
        messages.save(message);

        Instant now = Instant.now();
        AgentExecutionEntity run = new AgentExecutionEntity();
        run.setExecutionId(ids.next(ID_PREFIX_EXECUTION));
        run.setConversationSessionId(conversationSessionId);
        run.setTriggeringConversationMessageId(message.getConversationMessageId());
        run.setStatus(STATUS_QUEUED);
        run.setPhase(PHASE_QUEUED);
        run.setExecutionAttemptNumber(0L);
        run.setOwnerUserId(OperatorContext.requiredOperator().userId());
        run.setRequestId(requestId);
        run.setQueuedAt(now);
        run.setRecoveryAttemptCount(0);
        run.setRequestType(REQUEST_TYPE);
        run.setRequestIdempotencyKey(idempotencyKey);
        run.setRequestPayloadHash(payloadHash);
        run.setExecutionBudgetMs(DEFAULT_EXECUTION_BUDGET_MS);
        run.setActiveExecutionMs(0L);
        run.setVersion(0L);
        agentExecutions.save(run);

        message.setExecutionId(run.getExecutionId());
        messages.updateById(message);

        AgentExecutionEventEntity event = new AgentExecutionEventEntity();
        event.setExecutionEventId(ids.next(ID_PREFIX_EXECUTION_EVENT));
        event.setConversationSessionId(conversationSessionId);
        event.setExecutionId(run.getExecutionId());
        event.setExecutionEventSequence(executionEventSequence);
        event.setType(EVENT_EXECUTION_QUEUED);
        event.setOccurredAt(now);
        event.setVersion(0L);
        events.save(event);

        AgentExecutionStreamCheckpointEntity stream = new AgentExecutionStreamCheckpointEntity();
        stream.setExecutionStreamCheckpointId(ids.next(ID_PREFIX_EXECUTION_STREAM_CHECKPOINT));
        stream.setExecutionId(run.getExecutionId());
        stream.setExecutionAttemptNumber(0L);
        stream.setModelInvocationSequence(0L);
        stream.setPhase(PHASE_QUEUED);
        stream.setOutputDeltaSequence(0L);
        stream.setReasoning(EMPTY_TEXT);
        stream.setAnswer(EMPTY_TEXT);
        stream.setVersion(0L);
        streamCheckpoints.save(stream);
        return session;
    }

    private ConversationSessionEntity replayAfterConstraintConflict(
            String conversationSessionId, String idempotencyKey, String payloadHash) {
        AgentExecutionEntity replay =
                agentExecutions.findRequest(conversationSessionId, REQUEST_TYPE, idempotencyKey);
        if (replay == null) {
            return null;
        }
        requireSamePayload(replay, payloadHash);
        ConversationSessionEntity session = sessions.findByUuid(conversationSessionId);
        if (session == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_CONVERSATION_SESSION_NOT_FOUND);
        }
        return session;
    }

    private static void requireSamePayload(AgentExecutionEntity run, String payloadHash) {
        if (!payloadHash.equals(run.getRequestPayloadHash())) {
            throw new IdempotencyKeyReusedException();
        }
    }

    static String payloadHash(String content) {
        String normalized =
                Normalizer.normalize(
                        content.replace(WINDOWS_LINE_SEPARATOR, LINE_SEPARATOR).replace('\r', '\n'),
                        Normalizer.Form.NFC);
        try {
            byte[] digest =
                    MessageDigest.getInstance(HASH_ALGORITHM_SHA_256)
                            .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    HASH_ALGORITHM_SHA_256 + ERROR_HASH_ALGORITHM_UNAVAILABLE_SUFFIX, impossible);
        }
    }
}
