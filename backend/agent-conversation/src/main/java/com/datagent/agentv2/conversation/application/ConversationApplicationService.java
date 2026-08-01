package com.datagent.agentv2.conversation.application;

import com.datagent.agentv2.persistence.entity.AgentMessageEntity;
import com.datagent.agentv2.persistence.service.AgentMessageService;
import com.datagent.agentv2.persistence.entity.AgentRunEntity;
import com.datagent.agentv2.persistence.entity.AgentRunEventEntity;
import com.datagent.agentv2.persistence.service.AgentRunEventService;
import com.datagent.agentv2.persistence.service.AgentRunService;
import com.datagent.agentv2.persistence.entity.AgentRunStreamEntity;
import com.datagent.agentv2.persistence.service.AgentRunStreamService;
import com.datagent.agentv2.persistence.entity.AgentSessionEntity;
import com.datagent.agentv2.persistence.service.AgentSessionService;
import com.datagent.agentv2.platform.context.OperatorContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversationApplicationService {

    private final AgentSessionService sessions;
    private final AgentMessageService messages;
    private final AgentRunService runs;
    private final AgentRunEventService events;
    private final AgentRunStreamService streams;
    private final IdGenerator ids;
    private final Clock clock;
    private final String instanceId = "instance_" + UUID.randomUUID();

    public ConversationApplicationService(
            AgentSessionService sessions,
            AgentMessageService messages,
            AgentRunService runs,
            AgentRunEventService events,
            AgentRunStreamService streams,
            IdGenerator ids,
            Clock clock) {
        this.sessions = sessions;
        this.messages = messages;
        this.runs = runs;
        this.events = events;
        this.streams = streams;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    public ConversationView create(String title, String tag) {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setId(ids.next("session"));
        session.setTitle(normalizedTitle(title));
        session.setTag(tag);
        session.setSessionType("INTERACTIVE");
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

    @Transactional
    public ConversationView submitMessage(String sessionId, String content, String requestId) {
        AgentSessionEntity session = requiredSession(sessionId);
        long messageSequence = session.getMessageSequence() + 1;
        long eventSequence = session.getEventSequence() + 1;
        session.setMessageSequence(messageSequence);
        session.setEventSequence(eventSequence);
        if (!sessions.updateById(session)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "session changed concurrently");
        }

        AgentMessageEntity message = new AgentMessageEntity();
        message.setId(ids.next("message"));
        message.setSessionId(sessionId);
        message.setRole("user");
        message.setContent(content);
        message.setSequence(messageSequence);
        message.setAuthorUserId(OperatorContext.requiredOperator().userId());
        message.setVersion(0L);
        messages.save(message);

        Instant now = clock.instant();
        AgentRunEntity run = new AgentRunEntity();
        run.setId(ids.next("run"));
        run.setSessionId(sessionId);
        run.setTriggeringMessageId(message.getId());
        run.setStatus("running");
        run.setPhase("RECEIVED");
        run.setExecutionAttempt(1L);
        run.setOwnerInstanceId(instanceId);
        run.setRequestId(requestId);
        run.setStartedAt(now);
        run.setHardDeadlineAt(now.plus(Duration.ofMinutes(5)));
        run.setVersion(0L);
        runs.save(run);

        message.setRunId(run.getId());
        messages.updateById(message);

        AgentRunEventEntity event = new AgentRunEventEntity();
        event.setId(ids.next("event"));
        event.setSessionId(sessionId);
        event.setRunId(run.getId());
        event.setSequence(eventSequence);
        event.setType("run_started");
        event.setOccurredAt(now);
        event.setVersion(0L);
        events.save(event);

        AgentRunStreamEntity stream = new AgentRunStreamEntity();
        stream.setRunId(run.getId());
        stream.setExecutionAttempt(1L);
        stream.setPhase("RECEIVED");
        stream.setStreamSequence(0L);
        stream.setThinking("");
        stream.setAnswer("");
        stream.setVersion(0L);
        streams.save(stream);

        return snapshot(session);
    }

    private AgentSessionEntity requiredSession(String sessionId) {
        AgentSessionEntity session = sessions.getById(sessionId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "agent session not found");
        }
        return session;
    }

    private ConversationView snapshot(AgentSessionEntity session) {
        List<ConversationView.MessageView> messageViews =
                messages
                        .lambdaQuery()
                        .eq(AgentMessageEntity::getSessionId, session.getId())
                        .orderByAsc(AgentMessageEntity::getSequence)
                        .list()
                        .stream()
                        .map(this::messageView)
                        .toList();
        ConversationView.RunView runView =
                runs
                        .lambdaQuery()
                        .eq(AgentRunEntity::getSessionId, session.getId())
                        .orderByDesc(AgentRunEntity::getCreatedAt)
                        .list()
                        .stream()
                        .findFirst()
                        .map(run -> runView(run, messageViews))
                        .orElse(null);
        return new ConversationView(
                session.getId(),
                session.getTitle(),
                session.getTag() == null ? "" : session.getTag(),
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
                message.getId(),
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
                        .eq(AgentRunEventEntity::getRunId, run.getId())
                        .orderByAsc(AgentRunEventEntity::getSequence)
                        .list()
                        .stream()
                        .map(event -> eventView(event, run))
                        .toList();
        AgentRunStreamEntity stream = streams.getById(run.getId());
        String objective =
                messageViews.stream()
                        .filter(message -> message.id().equals(run.getTriggeringMessageId()))
                        .map(ConversationView.MessageView::content)
                        .findFirst()
                        .orElse("");
        return new ConversationView.RunView(
                run.getId(),
                run.getStatus(),
                progress(run.getPhase()),
                objective,
                eventViews,
                null,
                null,
                stream == null
                        ? null
                        : new ConversationView.RunStreamView(
                                stream.getExecutionAttempt(),
                                stream.getPhase(),
                                stream.getStreamSequence(),
                                stream.getThinking(),
                                stream.getAnswer()));
    }

    private ConversationView.RunEventView eventView(AgentRunEventEntity event, AgentRunEntity run) {
        return new ConversationView.RunEventView(
                event.getId(),
                event.getSequence(),
                event.getType(),
                event.getOccurredAt(),
                event.getRunId(),
                run.getTriggeringMessageId(),
                "Run started",
                "Preparing the model request",
                null,
                Map.of(),
                Map.of(),
                null,
                null,
                run.getStatus());
    }

    private static int progress(String phase) {
        return switch (phase) {
            case "RECEIVED" -> 5;
            case "PREPARING_CONTEXT" -> 15;
            case "CONNECTING_MODEL" -> 25;
            case "THINKING" -> 45;
            case "ANSWERING" -> 70;
            case "PERSISTING" -> 90;
            case "COMPLETED" -> 100;
            default -> 0;
        };
    }

    private static String normalizedTitle(String title) {
        return title == null || title.isBlank() ? "New conversation" : title.trim();
    }
}
