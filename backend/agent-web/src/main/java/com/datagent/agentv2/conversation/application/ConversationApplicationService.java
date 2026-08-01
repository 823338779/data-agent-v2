package com.datagent.agentv2.conversation.application;

import static com.datagent.agentv2.conversation.RunConstants.DEFAULT_CONVERSATION_TITLE;
import static com.datagent.agentv2.conversation.RunConstants.EMPTY_TEXT;
import static com.datagent.agentv2.conversation.RunConstants.ERROR_AGENT_RUN_NOT_FOUND;
import static com.datagent.agentv2.conversation.RunConstants.ERROR_AGENT_SESSION_NOT_FOUND;
import static com.datagent.agentv2.conversation.RunConstants.EVENT_DETAIL_RUN_QUEUED;
import static com.datagent.agentv2.conversation.RunConstants.EVENT_DETAIL_RUN_STARTED;
import static com.datagent.agentv2.conversation.RunConstants.EVENT_RUN_QUEUED;
import static com.datagent.agentv2.conversation.RunConstants.EVENT_TITLE_RUN_QUEUED;
import static com.datagent.agentv2.conversation.RunConstants.EVENT_TITLE_RUN_STARTED;
import static com.datagent.agentv2.conversation.RunConstants.ID_PREFIX_SESSION;
import static com.datagent.agentv2.conversation.RunConstants.PHASE_ANSWERING;
import static com.datagent.agentv2.conversation.RunConstants.PHASE_COMPLETED;
import static com.datagent.agentv2.conversation.RunConstants.PHASE_CONNECTING_MODEL;
import static com.datagent.agentv2.conversation.RunConstants.PHASE_PERSISTING;
import static com.datagent.agentv2.conversation.RunConstants.PHASE_PREPARING_CONTEXT;
import static com.datagent.agentv2.conversation.RunConstants.PHASE_RECEIVED;
import static com.datagent.agentv2.conversation.RunConstants.PHASE_THINKING;
import static com.datagent.agentv2.conversation.RunConstants.SESSION_TYPE_INTERACTIVE;

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
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ConversationApplicationService {

    private final AgentSessionService sessions;
    private final AgentMessageService messages;
    private final AgentRunService runs;
    private final AgentRunEventService events;
    private final AgentRunStreamService streams;
    private final RunSubmissionService submissions;
    private final IdGenerator ids;

    @Transactional
    public ConversationView create(String title, String tag) {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId(ids.next(ID_PREFIX_SESSION));
        session.setTitle(normalizedTitle(title));
        session.setTag(tag);
        session.setSessionType(SESSION_TYPE_INTERACTIVE);
        session.setCreatedBy(OperatorContext.requiredOperator().userId());
        session.setMessageSequence(0L);
        session.setEventSequence(0L);
        session.setVersion(0L);
        sessions.save(session);
        return snapshot(session);
    }

    @Transactional(readOnly = true)
    public List<ConversationView> list() {
        return sessions.lambdaQuery().orderByDesc(AgentSessionEntity::getUpdatedAt).list().stream()
                .map(this::snapshot)
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationView get(String sessionId) {
        return snapshot(requiredSession(sessionId));
    }

    @Transactional(readOnly = true)
    public List<ConversationView.RunEventView> replayEvents(String sessionId) {
        ConversationView conversation = snapshot(requiredSession(sessionId));
        return conversation.run() == null ? List.of() : conversation.run().events();
    }

    @Transactional(readOnly = true)
    public void requireAccessibleSession(String sessionId) {
        // Resolve through the tenant-filtered persistence service before registering an SSE client.
        requiredSession(sessionId);
    }

    @Transactional(readOnly = true)
    public ConversationView.RunEventView eventView(AgentRunEventEntity event) {
        // Re-read the committed Run so live and replayed events expose the same public shape.
        AgentRunEntity run =
                runs.lambdaQuery()
                        .eq(AgentRunEntity::getRunId, event.getRunId())
                        .one();
        if (run == null || !event.getSessionId().equals(run.getSessionId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ERROR_AGENT_RUN_NOT_FOUND);
        }
        return eventView(event, run);
    }

    public ConversationView submitMessage(
            String sessionId, String content, String idempotencyKey, String requestId) {
        return snapshot(submissions.submit(sessionId, content, idempotencyKey, requestId));
    }

    @Transactional(readOnly = true)
    public ConversationView.RunView controlView(String sessionId, AgentRunEntity run) {
        if (!sessionId.equals(run.getSessionId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ERROR_AGENT_RUN_NOT_FOUND);
        }
        List<ConversationView.MessageView> messageViews =
                messages.findUserVisibleBySessionId(sessionId).stream()
                        .map(this::messageView)
                        .toList();
        return runView(run, messageViews);
    }

    private AgentSessionEntity requiredSession(String sessionId) {
        AgentSessionEntity session = sessions.findByUuid(sessionId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ERROR_AGENT_SESSION_NOT_FOUND);
        }
        return session;
    }

    private ConversationView snapshot(AgentSessionEntity session) {
        List<ConversationView.MessageView> messageViews =
                messages.findUserVisibleBySessionId(session.getSessionId()).stream()
                        .map(this::messageView)
                        .toList();
        ConversationView.RunView runView =
                runs
                        .lambdaQuery()
                        .eq(AgentRunEntity::getSessionId, session.getSessionId())
                        .orderByDesc(AgentRunEntity::getCreatedAt)
                        .list()
                        .stream()
                        .findFirst()
                        .map(run -> runView(run, messageViews))
                        .orElse(null);
        return new ConversationView(
                session.getSessionId(),
                session.getTitle(),
                session.getTag() == null ? EMPTY_TEXT : session.getTag(),
                session.getSessionType(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                session.getVersion(),
                0,
                null,
                messageViews,
                runView);
    }

    private ConversationView.MessageView messageView(AgentMessageEntity message) {
        String author =
                message.getAuthorUserId() == null ? message.getRole() : message.getAuthorUserId();
        return new ConversationView.MessageView(
                message.getMessageId(),
                message.getRole(),
                author,
                message.getContent(),
                message.getCreatedAt(),
                List.of());
    }

    private ConversationView.RunView runView(
            AgentRunEntity run, List<ConversationView.MessageView> messageViews) {
        List<ConversationView.RunEventView> eventViews =
                events
                        .lambdaQuery()
                        .eq(AgentRunEventEntity::getRunId, run.getRunId())
                        .orderByAsc(AgentRunEventEntity::getSequence)
                        .list()
                        .stream()
                        .map(event -> eventView(event, run))
                        .toList();
        AgentRunStreamEntity stream = streams.findByRunId(run.getRunId());
        String objective =
                messageViews.stream()
                        .filter(message -> message.id().equals(run.getTriggeringMessageId()))
                        .map(ConversationView.MessageView::content)
                        .findFirst()
                        .orElse(EMPTY_TEXT);
        return new ConversationView.RunView(
                run.getRunId(),
                run.getStatus(),
                progress(run.getPhase()),
                objective,
                run.getCancellationRequestedAt(),
                eventViews,
                null,
                null,
                stream == null
                        ? null
                        : new ConversationView.RunStreamView(
                                stream.getExecutionAttempt(),
                                stream.getModelTurnSequence(),
                                stream.getPhase(),
                                stream.getStreamSequence(),
                                stream.getReasoning(),
                                stream.getAnswer()));
    }

    private ConversationView.RunEventView eventView(AgentRunEventEntity event, AgentRunEntity run) {
        return new ConversationView.RunEventView(
                event.getEventId(),
                event.getSequence(),
                event.getType(),
                event.getOccurredAt(),
                event.getRunId(),
                run.getTriggeringMessageId(),
                event.getType().equals(EVENT_RUN_QUEUED)
                        ? EVENT_TITLE_RUN_QUEUED
                        : EVENT_TITLE_RUN_STARTED,
                event.getType().equals(EVENT_RUN_QUEUED)
                        ? EVENT_DETAIL_RUN_QUEUED
                        : EVENT_DETAIL_RUN_STARTED,
                null,
                Map.of(),
                Map.of(),
                null,
                null,
                run.getStatus());
    }

    private static int progress(String phase) {
        return switch (phase) {
            case PHASE_RECEIVED -> 5;
            case PHASE_PREPARING_CONTEXT -> 15;
            case PHASE_CONNECTING_MODEL -> 25;
            case PHASE_THINKING -> 45;
            case PHASE_ANSWERING -> 70;
            case PHASE_PERSISTING -> 90;
            case PHASE_COMPLETED -> 100;
            default -> 0;
        };
    }

    private static String normalizedTitle(String title) {
        return title == null || title.isBlank() ? DEFAULT_CONVERSATION_TITLE : title.trim();
    }
}
