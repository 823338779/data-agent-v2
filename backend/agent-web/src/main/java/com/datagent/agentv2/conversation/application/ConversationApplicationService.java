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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ConversationApplicationService {

    private static final String ROLE_ASSISTANT = "assistant";

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
    public List<ConversationView.RunEventView> replayEvents(String sessionId, long afterSequence) {
        requiredSession(sessionId);
        return events
                .lambdaQuery()
                .eq(AgentRunEventEntity::getSessionId, sessionId)
                .gt(AgentRunEventEntity::getSequence, afterSequence)
                .orderByAsc(AgentRunEventEntity::getSequence)
                .list()
                .stream()
                .map(this::eventView)
                .toList();
    }

    @Transactional(readOnly = true)
    public StreamSnapshotView currentStream(String sessionId) {
        requiredSession(sessionId);
        List<AgentRunEntity> sessionRuns =
                runs.lambdaQuery()
                        .eq(AgentRunEntity::getSessionId, sessionId)
                        .orderByDesc(AgentRunEntity::getCreatedAt)
                        .list();
        if (sessionRuns.isEmpty()) {
            return null;
        }
        AgentRunEntity run = sessionRuns.getFirst();
        AgentRunStreamEntity stream = streams.findByRunId(run.getRunId());
        if (stream == null) {
            return null;
        }
        return streamSnapshot(run, stream);
    }

    @Transactional(readOnly = true)
    public StreamReconciliationView reconcileStreams(Map<String, Long> afterSequenceBySession) {
        if (afterSequenceBySession.isEmpty()) {
            return new StreamReconciliationView(Map.of(), Map.of());
        }
        Set<String> sessionIds = afterSequenceBySession.keySet();
        long minimumSequence =
                afterSequenceBySession.values().stream().mapToLong(Long::longValue).min().orElse(0);

        // 一次读取租户内所有活跃 session 的增量事件，再按各连接最小 cursor 过滤。
        List<AgentRunEventEntity> pendingEvents =
                events.lambdaQuery()
                        .in(AgentRunEventEntity::getSessionId, sessionIds)
                        .gt(AgentRunEventEntity::getSequence, minimumSequence)
                        .orderByAsc(AgentRunEventEntity::getSessionId)
                        .orderByAsc(AgentRunEventEntity::getSequence)
                        .list();
        Set<String> eventRunIds =
                pendingEvents.stream()
                        .map(AgentRunEventEntity::getRunId)
                        .collect(Collectors.toSet());
        Map<String, AgentRunEntity> eventRuns =
                eventRunIds.isEmpty()
                        ? Map.of()
                        : runs
                                .lambdaQuery()
                                .in(AgentRunEntity::getRunId, eventRunIds)
                                .list()
                                .stream()
                                .collect(
                                        Collectors.toMap(
                                                AgentRunEntity::getRunId, Function.identity()));
        Map<String, List<ConversationView.RunEventView>> eventsBySession = new HashMap<>();
        for (AgentRunEventEntity event : pendingEvents) {
            if (event.getSequence() <= afterSequenceBySession.get(event.getSessionId())) {
                continue;
            }
            AgentRunEntity run = eventRuns.get(event.getRunId());
            if (run != null && event.getSessionId().equals(run.getSessionId())) {
                eventsBySession
                        .computeIfAbsent(
                                event.getSessionId(), ignored -> new java.util.ArrayList<>())
                        .add(eventView(event, run));
            }
        }

        // 每个活跃 session 只选择最新 Run，并一次读取对应 checkpoint 投影。
        Map<String, AgentRunEntity> latestRuns = new LinkedHashMap<>();
        runs.lambdaQuery()
                .in(AgentRunEntity::getSessionId, sessionIds)
                .orderByDesc(AgentRunEntity::getCreatedAt)
                .list()
                .forEach(run -> latestRuns.putIfAbsent(run.getSessionId(), run));
        Set<String> latestRunIds =
                latestRuns.values().stream()
                        .map(AgentRunEntity::getRunId)
                        .collect(Collectors.toSet());
        Map<String, AgentRunStreamEntity> streamsByRunId =
                latestRunIds.isEmpty()
                        ? Map.of()
                        : streams
                                .lambdaQuery()
                                .in(AgentRunStreamEntity::getRunId, latestRunIds)
                                .list()
                                .stream()
                                .collect(
                                        Collectors.toMap(
                                                AgentRunStreamEntity::getRunId,
                                                Function.identity()));
        Map<String, StreamSnapshotView> snapshotsBySession = new HashMap<>();
        latestRuns.forEach(
                (sessionId, run) -> {
                    AgentRunStreamEntity stream = streamsByRunId.get(run.getRunId());
                    if (stream != null) {
                        snapshotsBySession.put(sessionId, streamSnapshot(run, stream));
                    }
                });
        return new StreamReconciliationView(
                immutableEventMap(eventsBySession), Map.copyOf(snapshotsBySession));
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
                runs.lambdaQuery().eq(AgentRunEntity::getRunId, event.getRunId()).one();
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
                        .map(message -> messageView(message, null))
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
        // 先构建不含 Run 的消息视图，供每个 Run 解析触发消息形成 objective。
        List<AgentMessageEntity> messageEntities =
                messages.findUserVisibleBySessionId(session.getSessionId());
        List<ConversationView.MessageView> baseMessageViews =
                messageEntities.stream().map(message -> messageView(message, null)).toList();
        List<AgentRunEntity> runEntities =
                runs.lambdaQuery()
                        .eq(AgentRunEntity::getSessionId, session.getSessionId())
                        .orderByDesc(AgentRunEntity::getCreatedAt)
                        .list();

        // 将全部历史 Run 按 runId 关联回 assistant 消息，同时保留最新 Run 作为会话级实时状态。
        Map<String, ConversationView.RunView> runViewsById =
                runEntities.stream()
                        .map(run -> runView(run, baseMessageViews))
                        .collect(
                                Collectors.toMap(
                                        ConversationView.RunView::id,
                                        Function.identity(),
                                        (first, ignored) -> first));
        List<ConversationView.MessageView> messageViews =
                messageEntities.stream()
                        .map(
                                message ->
                                        messageView(
                                                message,
                                                ROLE_ASSISTANT.equals(message.getRole())
                                                        ? runViewsById.get(message.getRunId())
                                                        : null))
                        .toList();
        ConversationView.RunView latestRunView =
                runEntities.isEmpty() ? null : runViewsById.get(runEntities.getFirst().getRunId());
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
                latestRunView);
    }

    private ConversationView.MessageView messageView(
            AgentMessageEntity message, ConversationView.RunView run) {
        String author =
                message.getAuthorUserId() == null ? message.getRole() : message.getAuthorUserId();
        return new ConversationView.MessageView(
                message.getMessageId(),
                message.getRole(),
                author,
                message.getContent(),
                message.getCreatedAt(),
                List.of(),
                run);
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

    private static StreamSnapshotView streamSnapshot(
            AgentRunEntity run, AgentRunStreamEntity stream) {
        return new StreamSnapshotView(
                run.getRunId(),
                stream.getExecutionAttempt(),
                stream.getModelTurnSequence(),
                stream.getPhase(),
                stream.getStreamSequence(),
                stream.getReasoning(),
                stream.getAnswer(),
                stream.getCheckpointedAt());
    }

    private static Map<String, List<ConversationView.RunEventView>> immutableEventMap(
            Map<String, List<ConversationView.RunEventView>> eventsBySession) {
        Map<String, List<ConversationView.RunEventView>> result = new HashMap<>();
        eventsBySession.forEach((sessionId, values) -> result.put(sessionId, List.copyOf(values)));
        return Map.copyOf(result);
    }

    /** 数据库中一个 Run 的完整流式投影，用于 SSE 建连和跳号恢复。 */
    public record StreamSnapshotView(
            String runId,
            long executionAttempt,
            long modelTurnSequence,
            String phase,
            long streamSequence,
            String reasoning,
            String answer,
            java.time.Instant checkpointedAt) {}

    /** 一次租户级 reconciliation 查询返回的持久事件和最新流投影。 */
    public record StreamReconciliationView(
            Map<String, List<ConversationView.RunEventView>> eventsBySession,
            Map<String, StreamSnapshotView> snapshotsBySession) {}
}
