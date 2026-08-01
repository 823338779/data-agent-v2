package com.datagent.agentv2.conversation.application;

import static com.datagent.agentv2.conversation.RunConstants.EMPTY_TEXT;
import static com.datagent.agentv2.conversation.RunConstants.ERROR_AGENT_SESSION_NOT_FOUND;
import static com.datagent.agentv2.conversation.RunConstants.ERROR_HASH_ALGORITHM_UNAVAILABLE_SUFFIX;
import static com.datagent.agentv2.conversation.RunConstants.ERROR_IDEMPOTENCY_KEY_BLANK;
import static com.datagent.agentv2.conversation.RunConstants.ERROR_SESSION_CHANGED_CONCURRENTLY;
import static com.datagent.agentv2.conversation.RunConstants.EVENT_RUN_QUEUED;
import static com.datagent.agentv2.conversation.RunConstants.HASH_ALGORITHM_SHA_256;
import static com.datagent.agentv2.conversation.RunConstants.ID_PREFIX_EVENT;
import static com.datagent.agentv2.conversation.RunConstants.ID_PREFIX_MESSAGE;
import static com.datagent.agentv2.conversation.RunConstants.ID_PREFIX_RUN;
import static com.datagent.agentv2.conversation.RunConstants.ID_PREFIX_STREAM;
import static com.datagent.agentv2.conversation.RunConstants.LINE_SEPARATOR;
import static com.datagent.agentv2.conversation.RunConstants.MESSAGE_ROLE_USER;
import static com.datagent.agentv2.conversation.RunConstants.PHASE_QUEUED;
import static com.datagent.agentv2.conversation.RunConstants.REQUEST_MESSAGE_SUBMISSION;
import static com.datagent.agentv2.conversation.RunConstants.STATUS_QUEUED;
import static com.datagent.agentv2.conversation.RunConstants.WINDOWS_LINE_SEPARATOR;

import com.datagent.agentv2.persistence.entity.AgentMessageEntity;
import com.datagent.agentv2.persistence.entity.AgentRunEntity;
import com.datagent.agentv2.persistence.entity.AgentRunEventEntity;
import com.datagent.agentv2.persistence.entity.AgentRunStreamEntity;
import com.datagent.agentv2.persistence.entity.AgentSessionEntity;
import com.datagent.agentv2.persistence.service.AgentMessageService;
import com.datagent.agentv2.persistence.service.AgentRunEventService;
import com.datagent.agentv2.persistence.service.AgentRunService;
import com.datagent.agentv2.persistence.service.AgentRunStreamService;
import com.datagent.agentv2.persistence.service.AgentSessionService;
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
public final class RunSubmissionService {

    static final String REQUEST_TYPE = REQUEST_MESSAGE_SUBMISSION;
    static final long DEFAULT_EXECUTION_BUDGET_MS = 600_000L;

    private final AgentSessionService sessions;
    private final AgentMessageService messages;
    private final AgentRunService runs;
    private final AgentRunEventService events;
    private final AgentRunStreamService streams;
    private final IdGenerator ids;
    private final TransactionTemplate transactions;

    public RunSubmissionService(
            AgentSessionService sessions,
            AgentMessageService messages,
            AgentRunService runs,
            AgentRunEventService events,
            AgentRunStreamService streams,
            IdGenerator ids,
            PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.messages = messages;
        this.runs = runs;
        this.events = events;
        this.streams = streams;
        this.ids = ids;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public AgentSessionEntity submit(
            String sessionId, String content, String idempotencyKey, String requestId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_IDEMPOTENCY_KEY_BLANK);
        }
        String payloadHash = payloadHash(content);
        try {
            return Objects.requireNonNull(
                    transactions.execute(
                            ignored ->
                                    createOrReplay(
                                            sessionId,
                                            content,
                                            idempotencyKey,
                                            payloadHash,
                                            requestId)));
        } catch (DataIntegrityViolationException conflict) {
            AgentSessionEntity replay =
                    transactions.execute(
                            ignored ->
                                    replayAfterConstraintConflict(
                                            sessionId, idempotencyKey, payloadHash));
            if (replay != null) {
                return replay;
            }
            throw conflict;
        }
    }

    private AgentSessionEntity createOrReplay(
            String sessionId,
            String content,
            String idempotencyKey,
            String payloadHash,
            String requestId) {
        AgentSessionEntity session = sessions.lockById(sessionId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ERROR_AGENT_SESSION_NOT_FOUND);
        }
        AgentRunEntity replay = runs.findRequest(sessionId, REQUEST_TYPE, idempotencyKey);
        if (replay != null) {
            requireSamePayload(replay, payloadHash);
            return session;
        }
        if (runs.hasActiveRun(sessionId)) {
            throw new ActiveRunConflictException();
        }

        long messageSequence = session.getMessageSequence() + 1;
        long eventSequence = session.getEventSequence() + 1;
        session.setMessageSequence(messageSequence);
        session.setEventSequence(eventSequence);
        if (!sessions.updateById(session)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, ERROR_SESSION_CHANGED_CONCURRENTLY);
        }

        AgentMessageEntity message = new AgentMessageEntity();
        message.setMessageId(ids.next(ID_PREFIX_MESSAGE));
        message.setSessionId(sessionId);
        message.setRole(MESSAGE_ROLE_USER);
        message.setContent(content);
        message.setSequence(messageSequence);
        message.setAuthorUserId(OperatorContext.requiredOperator().userId());
        message.setVersion(0L);
        messages.save(message);

        Instant now = Instant.now();
        AgentRunEntity run = new AgentRunEntity();
        run.setRunId(ids.next(ID_PREFIX_RUN));
        run.setSessionId(sessionId);
        run.setTriggeringMessageId(message.getMessageId());
        run.setStatus(STATUS_QUEUED);
        run.setPhase(PHASE_QUEUED);
        run.setExecutionAttempt(0L);
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
        runs.save(run);

        message.setRunId(run.getRunId());
        messages.updateById(message);

        AgentRunEventEntity event = new AgentRunEventEntity();
        event.setEventId(ids.next(ID_PREFIX_EVENT));
        event.setSessionId(sessionId);
        event.setRunId(run.getRunId());
        event.setSequence(eventSequence);
        event.setType(EVENT_RUN_QUEUED);
        event.setOccurredAt(now);
        event.setVersion(0L);
        events.save(event);

        AgentRunStreamEntity stream = new AgentRunStreamEntity();
        stream.setStreamId(ids.next(ID_PREFIX_STREAM));
        stream.setRunId(run.getRunId());
        stream.setExecutionAttempt(0L);
        stream.setModelTurnSequence(0L);
        stream.setPhase(PHASE_QUEUED);
        stream.setStreamSequence(0L);
        stream.setReasoning(EMPTY_TEXT);
        stream.setAnswer(EMPTY_TEXT);
        stream.setVersion(0L);
        streams.save(stream);
        return session;
    }

    private AgentSessionEntity replayAfterConstraintConflict(
            String sessionId, String idempotencyKey, String payloadHash) {
        AgentRunEntity replay = runs.findRequest(sessionId, REQUEST_TYPE, idempotencyKey);
        if (replay == null) {
            return null;
        }
        requireSamePayload(replay, payloadHash);
        AgentSessionEntity session = sessions.findByUuid(sessionId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ERROR_AGENT_SESSION_NOT_FOUND);
        }
        return session;
    }

    private static void requireSamePayload(AgentRunEntity run, String payloadHash) {
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
