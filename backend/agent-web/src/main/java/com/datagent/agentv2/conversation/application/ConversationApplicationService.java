package com.datagent.agentv2.conversation.application;

import static com.datagent.agentv2.conversation.AgentExecutionConstants.DEFAULT_CONVERSATION_TITLE;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.EMPTY_TEXT;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.ERROR_AGENT_EXECUTION_NOT_FOUND;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.ERROR_CONVERSATION_SESSION_NOT_FOUND;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.EVENT_DETAIL_EXECUTION_QUEUED;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.EVENT_DETAIL_EXECUTION_STARTED;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.EVENT_EXECUTION_QUEUED;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.EVENT_TITLE_EXECUTION_QUEUED;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.EVENT_TITLE_EXECUTION_STARTED;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.ID_PREFIX_CONVERSATION_SESSION;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.PHASE_ANSWERING;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.PHASE_COMPLETED;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.PHASE_CONNECTING_MODEL;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.PHASE_PERSISTING;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.PHASE_PREPARING_CONTEXT;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.PHASE_RECEIVED;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.PHASE_THINKING;
import static com.datagent.agentv2.conversation.AgentExecutionConstants.SESSION_TYPE_INTERACTIVE;

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

    private final ConversationSessionService sessions;
    private final ConversationMessageService messages;
    private final AgentExecutionService agentExecutions;
    private final AgentExecutionEventService events;
    private final AgentExecutionStreamCheckpointService streamCheckpoints;
    private final AgentExecutionSubmissionService submissions;
    private final IdGenerator ids;

    @Transactional
    public ConversationView create(String title, String tag) {
        ConversationSessionEntity session = new ConversationSessionEntity();
        session.setConversationSessionId(ids.next(ID_PREFIX_CONVERSATION_SESSION));
        session.setTitle(normalizedTitle(title));
        session.setTag(tag);
        session.setSessionType(SESSION_TYPE_INTERACTIVE);
        session.setCreatedByUserId(OperatorContext.requiredOperator().userId());
        session.setConversationMessageSequence(0L);
        session.setExecutionEventSequence(0L);
        session.setVersion(0L);
        sessions.save(session);
        return snapshot(session);
    }

    @Transactional(readOnly = true)
    public List<ConversationView> list() {
        return sessions
                .lambdaQuery()
                .orderByDesc(ConversationSessionEntity::getUpdatedAt)
                .list()
                .stream()
                .map(this::snapshot)
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationView get(String conversationSessionId) {
        return snapshot(requiredSession(conversationSessionId));
    }

    @Transactional(readOnly = true)
    public List<ConversationView.AgentExecutionEventView> replayEvents(
            String conversationSessionId) {
        ConversationView conversation = snapshot(requiredSession(conversationSessionId));
        return conversation.execution() == null ? List.of() : conversation.execution().events();
    }

    @Transactional(readOnly = true)
    public List<ConversationView.AgentExecutionEventView> replayEvents(
            String conversationSessionId, long afterSequence) {
        requiredSession(conversationSessionId);
        return events
                .lambdaQuery()
                .eq(AgentExecutionEventEntity::getConversationSessionId, conversationSessionId)
                .gt(AgentExecutionEventEntity::getExecutionEventSequence, afterSequence)
                .orderByAsc(AgentExecutionEventEntity::getExecutionEventSequence)
                .list()
                .stream()
                .map(this::eventView)
                .toList();
    }

    @Transactional(readOnly = true)
    public ExecutionStreamCheckpointView currentStream(String conversationSessionId) {
        requiredSession(conversationSessionId);
        List<AgentExecutionEntity> sessionExecutions =
                agentExecutions
                        .lambdaQuery()
                        .eq(AgentExecutionEntity::getConversationSessionId, conversationSessionId)
                        .orderByDesc(AgentExecutionEntity::getCreatedAt)
                        .list();
        if (sessionExecutions.isEmpty()) {
            return null;
        }
        AgentExecutionEntity run = sessionExecutions.getFirst();
        AgentExecutionStreamCheckpointEntity stream =
                streamCheckpoints.findByExecutionId(run.getExecutionId());
        if (stream == null) {
            return null;
        }
        return streamCheckpointView(run, stream);
    }

    @Transactional(readOnly = true)
    public SessionStreamRecoveryView reconcileStreams(Map<String, Long> afterSequenceBySession) {
        if (afterSequenceBySession.isEmpty()) {
            return new SessionStreamRecoveryView(Map.of(), Map.of());
        }
        Set<String> conversationSessionIds = afterSequenceBySession.keySet();
        long minimumSequence =
                afterSequenceBySession.values().stream().mapToLong(Long::longValue).min().orElse(0);

        // 一次读取租户内所有活跃 session 的增量事件，再按各连接最小 cursor 过滤。
        List<AgentExecutionEventEntity> pendingEvents =
                events.lambdaQuery()
                        .in(
                                AgentExecutionEventEntity::getConversationSessionId,
                                conversationSessionIds)
                        .gt(AgentExecutionEventEntity::getExecutionEventSequence, minimumSequence)
                        .orderByAsc(AgentExecutionEventEntity::getConversationSessionId)
                        .orderByAsc(AgentExecutionEventEntity::getExecutionEventSequence)
                        .list();
        Set<String> eventExecutionIds =
                pendingEvents.stream()
                        .map(AgentExecutionEventEntity::getExecutionId)
                        .collect(Collectors.toSet());
        Map<String, AgentExecutionEntity> eventExecutions =
                eventExecutionIds.isEmpty()
                        ? Map.of()
                        : agentExecutions
                                .lambdaQuery()
                                .in(AgentExecutionEntity::getExecutionId, eventExecutionIds)
                                .list()
                                .stream()
                                .collect(
                                        Collectors.toMap(
                                                AgentExecutionEntity::getExecutionId,
                                                Function.identity()));
        Map<String, List<ConversationView.AgentExecutionEventView>> eventsBySession =
                new HashMap<>();
        for (AgentExecutionEventEntity event : pendingEvents) {
            if (event.getExecutionEventSequence()
                    <= afterSequenceBySession.get(event.getConversationSessionId())) {
                continue;
            }
            AgentExecutionEntity run = eventExecutions.get(event.getExecutionId());
            if (run != null
                    && event.getConversationSessionId().equals(run.getConversationSessionId())) {
                eventsBySession
                        .computeIfAbsent(
                                event.getConversationSessionId(),
                                ignored -> new java.util.ArrayList<>())
                        .add(eventView(event, run));
            }
        }

        // 每个活跃会话只选择最新 Agent 执行，并一次读取对应检查点投影。
        Map<String, AgentExecutionEntity> latestExecutions = new LinkedHashMap<>();
        agentExecutions
                .lambdaQuery()
                .in(AgentExecutionEntity::getConversationSessionId, conversationSessionIds)
                .orderByDesc(AgentExecutionEntity::getCreatedAt)
                .list()
                .forEach(run -> latestExecutions.putIfAbsent(run.getConversationSessionId(), run));
        Set<String> latestExecutionIds =
                latestExecutions.values().stream()
                        .map(AgentExecutionEntity::getExecutionId)
                        .collect(Collectors.toSet());
        Map<String, AgentExecutionStreamCheckpointEntity> streamsByExecutionId =
                latestExecutionIds.isEmpty()
                        ? Map.of()
                        : streamCheckpoints
                                .lambdaQuery()
                                .in(
                                        AgentExecutionStreamCheckpointEntity::getExecutionId,
                                        latestExecutionIds)
                                .list()
                                .stream()
                                .collect(
                                        Collectors.toMap(
                                                AgentExecutionStreamCheckpointEntity
                                                        ::getExecutionId,
                                                Function.identity()));
        Map<String, ExecutionStreamCheckpointView> snapshotsBySession = new HashMap<>();
        latestExecutions.forEach(
                (conversationSessionId, run) -> {
                    AgentExecutionStreamCheckpointEntity stream =
                            streamsByExecutionId.get(run.getExecutionId());
                    if (stream != null) {
                        snapshotsBySession.put(
                                conversationSessionId, streamCheckpointView(run, stream));
                    }
                });
        return new SessionStreamRecoveryView(
                immutableEventMap(eventsBySession), Map.copyOf(snapshotsBySession));
    }

    @Transactional(readOnly = true)
    public void requireAccessibleSession(String conversationSessionId) {
        // Resolve through the tenant-filtered persistence service before registering an SSE client.
        requiredSession(conversationSessionId);
    }

    @Transactional(readOnly = true)
    public ConversationView.AgentExecutionEventView eventView(AgentExecutionEventEntity event) {
        // 重新读取已提交的 Agent 执行，确保实时事件与重放事件使用相同公开结构。
        AgentExecutionEntity run =
                agentExecutions
                        .lambdaQuery()
                        .eq(AgentExecutionEntity::getExecutionId, event.getExecutionId())
                        .one();
        if (run == null
                || !event.getConversationSessionId().equals(run.getConversationSessionId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_AGENT_EXECUTION_NOT_FOUND);
        }
        return eventView(event, run);
    }

    public ConversationView submitMessage(
            String conversationSessionId, String content, String idempotencyKey, String requestId) {
        return snapshot(
                submissions.submit(conversationSessionId, content, idempotencyKey, requestId));
    }

    @Transactional(readOnly = true)
    public ConversationView.AgentExecutionView controlView(
            String conversationSessionId, AgentExecutionEntity run) {
        if (!conversationSessionId.equals(run.getConversationSessionId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_AGENT_EXECUTION_NOT_FOUND);
        }
        List<ConversationView.MessageView> messageViews =
                messages.findUserVisibleByConversationSessionId(conversationSessionId).stream()
                        .map(message -> messageView(message, null))
                        .toList();
        return executionView(run, messageViews);
    }

    private ConversationSessionEntity requiredSession(String conversationSessionId) {
        ConversationSessionEntity session = sessions.findByUuid(conversationSessionId);
        if (session == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_CONVERSATION_SESSION_NOT_FOUND);
        }
        return session;
    }

    private ConversationView snapshot(ConversationSessionEntity session) {
        // 先构建不含执行投影的消息视图，供每次 Agent 执行解析触发消息形成 objective。
        List<ConversationMessageEntity> messageEntities =
                messages.findUserVisibleByConversationSessionId(session.getConversationSessionId());
        List<ConversationView.MessageView> baseMessageViews =
                messageEntities.stream().map(message -> messageView(message, null)).toList();
        List<AgentExecutionEntity> executionEntities =
                agentExecutions
                        .lambdaQuery()
                        .eq(
                                AgentExecutionEntity::getConversationSessionId,
                                session.getConversationSessionId())
                        .orderByDesc(AgentExecutionEntity::getCreatedAt)
                        .list();

        // 将全部历史 Agent 执行按 executionId 关联回 assistant 消息，同时保留最新执行作为会话级实时状态。
        Map<String, ConversationView.AgentExecutionView> executionViewsById =
                executionEntities.stream()
                        .map(run -> executionView(run, baseMessageViews))
                        .collect(
                                Collectors.toMap(
                                        ConversationView.AgentExecutionView::executionId,
                                        Function.identity(),
                                        (first, ignored) -> first));
        List<ConversationView.MessageView> messageViews =
                messageEntities.stream()
                        .map(
                                message ->
                                        messageView(
                                                message,
                                                ROLE_ASSISTANT.equals(message.getRole())
                                                        ? executionViewsById.get(
                                                                message.getExecutionId())
                                                        : null))
                        .toList();
        ConversationView.AgentExecutionView latestAgentExecutionView =
                executionEntities.isEmpty()
                        ? null
                        : executionViewsById.get(executionEntities.getFirst().getExecutionId());
        return new ConversationView(
                session.getConversationSessionId(),
                session.getTitle(),
                session.getTag() == null ? EMPTY_TEXT : session.getTag(),
                session.getSessionType(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                session.getVersion(),
                0,
                null,
                messageViews,
                latestAgentExecutionView);
    }

    private ConversationView.MessageView messageView(
            ConversationMessageEntity message, ConversationView.AgentExecutionView run) {
        String author =
                message.getAuthorUserId() == null ? message.getRole() : message.getAuthorUserId();
        return new ConversationView.MessageView(
                message.getConversationMessageId(),
                message.getRole(),
                author,
                message.getContent(),
                message.getCreatedAt(),
                List.of(),
                run);
    }

    private ConversationView.AgentExecutionView executionView(
            AgentExecutionEntity run, List<ConversationView.MessageView> messageViews) {
        List<ConversationView.AgentExecutionEventView> eventViews =
                events
                        .lambdaQuery()
                        .eq(AgentExecutionEventEntity::getExecutionId, run.getExecutionId())
                        .orderByAsc(AgentExecutionEventEntity::getExecutionEventSequence)
                        .list()
                        .stream()
                        .map(event -> eventView(event, run))
                        .toList();
        AgentExecutionStreamCheckpointEntity stream =
                streamCheckpoints.findByExecutionId(run.getExecutionId());
        String objective =
                messageViews.stream()
                        .filter(
                                message ->
                                        message.conversationMessageId()
                                                .equals(run.getTriggeringConversationMessageId()))
                        .map(ConversationView.MessageView::content)
                        .findFirst()
                        .orElse(EMPTY_TEXT);
        return new ConversationView.AgentExecutionView(
                run.getExecutionId(),
                run.getStatus(),
                progress(run.getPhase()),
                objective,
                run.getCancellationRequestedAt(),
                eventViews,
                null,
                null,
                stream == null
                        ? null
                        : new ConversationView.AgentExecutionStreamCheckpointView(
                                stream.getExecutionAttemptNumber(),
                                stream.getModelInvocationSequence(),
                                stream.getPhase(),
                                stream.getOutputDeltaSequence(),
                                stream.getReasoning(),
                                stream.getAnswer()));
    }

    private ConversationView.AgentExecutionEventView eventView(
            AgentExecutionEventEntity event, AgentExecutionEntity run) {
        return new ConversationView.AgentExecutionEventView(
                event.getExecutionEventId(),
                event.getExecutionEventSequence(),
                event.getType(),
                event.getOccurredAt(),
                event.getExecutionId(),
                run.getTriggeringConversationMessageId(),
                event.getType().equals(EVENT_EXECUTION_QUEUED)
                        ? EVENT_TITLE_EXECUTION_QUEUED
                        : EVENT_TITLE_EXECUTION_STARTED,
                event.getType().equals(EVENT_EXECUTION_QUEUED)
                        ? EVENT_DETAIL_EXECUTION_QUEUED
                        : EVENT_DETAIL_EXECUTION_STARTED,
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

    private static ExecutionStreamCheckpointView streamCheckpointView(
            AgentExecutionEntity run, AgentExecutionStreamCheckpointEntity stream) {
        return new ExecutionStreamCheckpointView(
                run.getExecutionId(),
                stream.getExecutionAttemptNumber(),
                stream.getModelInvocationSequence(),
                stream.getPhase(),
                stream.getOutputDeltaSequence(),
                stream.getReasoning(),
                stream.getAnswer(),
                stream.getCheckpointedAt());
    }

    private static Map<String, List<ConversationView.AgentExecutionEventView>> immutableEventMap(
            Map<String, List<ConversationView.AgentExecutionEventView>> eventsBySession) {
        Map<String, List<ConversationView.AgentExecutionEventView>> result = new HashMap<>();
        eventsBySession.forEach(
                (conversationSessionId, values) ->
                        result.put(conversationSessionId, List.copyOf(values)));
        return Map.copyOf(result);
    }

    /** 数据库中一次 Agent 执行的完整流式投影，用于 SSE 建连和跳号恢复。 */
    public record ExecutionStreamCheckpointView(
            String executionId,
            long executionAttemptNumber,
            long modelInvocationSequence,
            String phase,
            long outputDeltaSequence,
            String reasoning,
            String answer,
            java.time.Instant checkpointedAt) {}

    /** 一次租户级 reconciliation 查询返回的持久事件和最新流投影。 */
    public record SessionStreamRecoveryView(
            Map<String, List<ConversationView.AgentExecutionEventView>> eventsBySession,
            Map<String, ExecutionStreamCheckpointView> snapshotsBySession) {}
}
