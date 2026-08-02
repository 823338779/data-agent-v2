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

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.AgentExecutionEvent;
import com.datagent.agentv2.conversation.domain.AgentExecutionStreamCheckpoint;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.ConversationSession;
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

    public ConversationSession submit(
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
            ConversationSession replay =
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

    private ConversationSession createOrReplay(
            String conversationSessionId,
            String content,
            String idempotencyKey,
            String payloadHash,
            String requestId) {
        ConversationSession session = sessions.lockById(conversationSessionId);
        if (session == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_CONVERSATION_SESSION_NOT_FOUND);
        }
        AgentExecution replay =
                agentExecutions.findRequest(conversationSessionId, REQUEST_TYPE, idempotencyKey);
        if (replay != null) {
            requireSamePayload(replay, payloadHash);
            return session;
        }
        if (agentExecutions.hasActiveExecution(conversationSessionId)) {
            throw new ActiveExecutionConflictException();
        }

        // Advance the session-owned sequences before creating their dependent records.
        ConversationSession.AllocatedSequences sequences = session.allocateSubmissionSequences();
        if (!sessions.updateById(session)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, ERROR_SESSION_CHANGED_CONCURRENTLY);
        }

        // Persist the user message first because the execution references it as its trigger.
        String operatorUserId = OperatorContext.requiredOperator().userId();
        ConversationMessage message =
                ConversationMessage.user(
                        ids.next(ID_PREFIX_CONVERSATION_MESSAGE),
                        conversationSessionId,
                        content,
                        sequences.conversationMessage(),
                        operatorUserId,
                        MESSAGE_ROLE_USER);
        messages.save(message);

        // Create the queued execution and complete the bidirectional message association.
        Instant now = Instant.now();
        AgentExecution run =
                AgentExecution.queued(
                        ids.next(ID_PREFIX_EXECUTION),
                        conversationSessionId,
                        message.getConversationMessageId(),
                        operatorUserId,
                        requestId,
                        now,
                        REQUEST_TYPE,
                        idempotencyKey,
                        payloadHash,
                        DEFAULT_EXECUTION_BUDGET_MS,
                        STATUS_QUEUED,
                        PHASE_QUEUED);
        agentExecutions.save(run);

        message.associateExecution(run.getExecutionId());
        messages.updateById(message);

        // Seed the durable event stream and model-output checkpoint for the queued execution.
        events.save(
                AgentExecutionEvent.occurred(
                        ids.next(ID_PREFIX_EXECUTION_EVENT),
                        conversationSessionId,
                        run.getExecutionId(),
                        sequences.executionEvent(),
                        EVENT_EXECUTION_QUEUED,
                        null,
                        now));
        streamCheckpoints.save(
                AgentExecutionStreamCheckpoint.initial(
                        ids.next(ID_PREFIX_EXECUTION_STREAM_CHECKPOINT),
                        run.getExecutionId(),
                        PHASE_QUEUED,
                        EMPTY_TEXT,
                        EMPTY_TEXT));
        return session;
    }

    private ConversationSession replayAfterConstraintConflict(
            String conversationSessionId, String idempotencyKey, String payloadHash) {
        AgentExecution replay =
                agentExecutions.findRequest(conversationSessionId, REQUEST_TYPE, idempotencyKey);
        if (replay == null) {
            return null;
        }
        requireSamePayload(replay, payloadHash);
        ConversationSession session = sessions.findByUuid(conversationSessionId);
        if (session == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_CONVERSATION_SESSION_NOT_FOUND);
        }
        return session;
    }

    private static void requireSamePayload(AgentExecution run, String payloadHash) {
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
