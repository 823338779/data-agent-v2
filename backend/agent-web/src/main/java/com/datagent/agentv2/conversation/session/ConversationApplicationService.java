package com.datagent.agentv2.conversation.session;

import static com.datagent.agentv2.conversation.execution.ExecutionConstants.DEFAULT_CONVERSATION_TITLE;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.EMPTY_TEXT;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_AGENT_EXECUTION_NOT_FOUND;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_CONVERSATION_SESSION_NOT_FOUND;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.EVENT_DETAIL_EXECUTION_QUEUED;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.EVENT_DETAIL_EXECUTION_STARTED;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.EVENT_TITLE_EXECUTION_QUEUED;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.EVENT_TITLE_EXECUTION_STARTED;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ID_PREFIX_CONVERSATION_SESSION;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.AgentExecutionEvent;
import com.datagent.agentv2.conversation.domain.AgentExecutionStreamCheckpoint;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.ConversationSession;
import com.datagent.agentv2.conversation.enums.ConversationSessionType;
import com.datagent.agentv2.conversation.enums.ExecutionEventType;
import com.datagent.agentv2.conversation.enums.ExecutionPhase;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import com.datagent.agentv2.conversation.enums.MessageRole;
import com.datagent.agentv2.conversation.enums.SessionExecutionStatus;
import com.datagent.agentv2.conversation.execution.AgentExecutionSubmissionService;
import com.datagent.agentv2.persistence.service.AgentExecutionEventService;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.AgentExecutionStreamCheckpointService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.platform.context.OperatorContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversationApplicationService {

    private final ConversationSessionService conversationSessionService;
    private final ConversationMessageService conversationMessageService;
    private final AgentExecutionService agentExecutionService;
    private final AgentExecutionEventService agentExecutionEventService;
    private final AgentExecutionStreamCheckpointService agentExecutionStreamCheckpointService;
    private final AgentExecutionSubmissionService agentExecutionSubmissionService;
    private final IdGenerator idGenerator;

    @Autowired
    public ConversationApplicationService(
            ConversationSessionService conversationSessionService,
            ConversationMessageService conversationMessageService,
            AgentExecutionService agentExecutionService,
            AgentExecutionEventService agentExecutionEventService,
            AgentExecutionStreamCheckpointService agentExecutionStreamCheckpointService,
            AgentExecutionSubmissionService agentExecutionSubmissionService,
            IdGenerator idGenerator) {
        this.conversationSessionService = conversationSessionService;
        this.conversationMessageService = conversationMessageService;
        this.agentExecutionService = agentExecutionService;
        this.agentExecutionEventService = agentExecutionEventService;
        this.agentExecutionStreamCheckpointService = agentExecutionStreamCheckpointService;
        this.agentExecutionSubmissionService = agentExecutionSubmissionService;
        this.idGenerator = idGenerator;
    }

    @Transactional
    public ConversationView create(String title, String tag) {
        ConversationSession session =
                ConversationSession.create(
                        idGenerator.next(ID_PREFIX_CONVERSATION_SESSION),
                        normalizedTitle(title),
                        tag,
                        ConversationSessionType.INTERACTIVE.value(),
                        OperatorContext.requiredUserId(),
                        Instant.now());
        conversationSessionService.save(session);
        return snapshot(session);
    }

    @Transactional(readOnly = true)
    public List<ConversationSummaryView> listSummaries() {
        List<ConversationSession> sessions = conversationSessionService.listByUpdatedAtDesc();
        if (sessions.isEmpty()) {
            return List.of();
        }
        // 每个会话只保留最新执行，摘要查询不再装配消息和历史事件。
        Map<String, AgentExecution> latestExecutions = new LinkedHashMap<>();
        agentExecutionService
                .findByConversationSessionIdsOrderByCreatedAtDesc(
                        sessions.stream()
                                .map(ConversationSession::getConversationSessionId)
                                .toList())
                .forEach(
                        execution ->
                                latestExecutions.putIfAbsent(
                                        execution.getConversationSessionId(), execution));
        // 持久化查询已经按会话更新时间降序返回；业务标识仅作为同时间戳的稳定次序。
        return sessions.stream()
                .map(
                        session -> {
                            AgentExecution execution =
                                    latestExecutions.get(session.getConversationSessionId());
                            return summary(session, execution);
                        })
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationView get(String conversationSessionId) {
        return snapshot(requiredSession(conversationSessionId));
    }

    /** 为首次 SSE 连接读取一次权威会话投影及其持久事件水位。 */
    @Transactional(readOnly = true)
    public SessionStreamBootstrapView streamBootstrap(String conversationSessionId) {
        ConversationSession session = requiredSession(conversationSessionId);
        return new SessionStreamBootstrapView(
                snapshot(session),
                session.getExecutionEventSequence() == null
                        ? 0L
                        : session.getExecutionEventSequence());
    }

    @Transactional(readOnly = true)
    public List<ConversationView.AgentExecutionUpdateView> replayEvents(
            String conversationSessionId, long afterSequence) {
        requiredSession(conversationSessionId);
        return agentExecutionEventService
                .findByConversationSessionIdAfter(conversationSessionId, afterSequence)
                .stream()
                .map(this::executionUpdate)
                .toList();
    }

    @Transactional(readOnly = true)
    public ExecutionStreamCheckpointView currentStream(String conversationSessionId) {
        requiredSession(conversationSessionId);
        List<AgentExecution> sessionExecutions =
                agentExecutionService.findByConversationSessionIdOrderByCreatedAtDesc(
                        conversationSessionId);
        if (sessionExecutions.isEmpty()) {
            return null;
        }
        AgentExecution run = sessionExecutions.getFirst();
        AgentExecutionStreamCheckpoint stream =
                agentExecutionStreamCheckpointService.findByExecutionId(run.getExecutionId());
        if (stream == null) {
            return null;
        }
        return streamCheckpointView(run, stream);
    }

    @Transactional(readOnly = true)
    public SessionStreamRecoveryView reconcileStreams(Map<String, Long> afterSequenceBySession) {
        if (afterSequenceBySession.isEmpty()) {
            return new SessionStreamRecoveryView(Map.of(), Map.of(), Map.of(), Map.of());
        }
        Set<String> conversationSessionIds = afterSequenceBySession.keySet();
        long minimumSequence =
                afterSequenceBySession.values().stream().mapToLong(Long::longValue).min().orElse(0);

        // 一次读取租户内所有活跃 session 的增量事件，再按各连接最小 cursor 过滤。
        List<AgentExecutionEvent> pendingEvents =
                agentExecutionEventService.findByConversationSessionIdsAfter(
                        conversationSessionIds, minimumSequence);
        Set<String> eventExecutionIds =
                pendingEvents.stream()
                        .map(AgentExecutionEvent::getExecutionId)
                        .collect(Collectors.toSet());
        Map<String, AgentExecution> eventExecutions =
                eventExecutionIds.isEmpty()
                        ? Map.of()
                        : agentExecutionService.findByExecutionIds(eventExecutionIds).stream()
                                .collect(
                                        Collectors.toMap(
                                                AgentExecution::getExecutionId,
                                                Function.identity()));
        Map<String, List<ConversationView.AgentExecutionUpdateView>> eventsBySession =
                new HashMap<>();
        for (AgentExecutionEvent event : pendingEvents) {
            if (event.getExecutionEventSequence()
                    <= afterSequenceBySession.get(event.getConversationSessionId())) {
                continue;
            }
            AgentExecution run = eventExecutions.get(event.getExecutionId());
            if (run != null
                    && event.getConversationSessionId().equals(run.getConversationSessionId())) {
                eventsBySession
                        .computeIfAbsent(
                                event.getConversationSessionId(),
                                ignored -> new java.util.ArrayList<>())
                        .add(executionUpdate(event));
            }
        }

        // 每个活跃会话只选择最新 Agent 执行，并一次读取对应检查点投影。
        Map<String, AgentExecution> latestExecutions = new LinkedHashMap<>();
        agentExecutionService
                .findByConversationSessionIdsOrderByCreatedAtDesc(conversationSessionIds)
                .forEach(run -> latestExecutions.putIfAbsent(run.getConversationSessionId(), run));
        Set<String> latestExecutionIds =
                latestExecutions.values().stream()
                        .map(AgentExecution::getExecutionId)
                        .collect(Collectors.toSet());
        Map<String, AgentExecutionStreamCheckpoint> streamsByExecutionId =
                latestExecutionIds.isEmpty()
                        ? Map.of()
                        : agentExecutionStreamCheckpointService
                                .findByExecutionIds(latestExecutionIds)
                                .stream()
                                .collect(
                                        Collectors.toMap(
                                                AgentExecutionStreamCheckpoint::getExecutionId,
                                                Function.identity()));
        Map<String, ExecutionStreamCheckpointView> snapshotsBySession = new HashMap<>();
        Map<String, Boolean> activeBySession = new HashMap<>();
        latestExecutions.forEach(
                (conversationSessionId, run) -> {
                    activeBySession.put(
                            conversationSessionId,
                            ExecutionStatus.QUEUED.value().equals(run.getStatus())
                                    || ExecutionStatus.RUNNING.value().equals(run.getStatus())
                                    || ExecutionStatus.WAITING_FOR_USER
                                            .value()
                                            .equals(run.getStatus())
                                    || ExecutionStatus.WAITING_CONFIRMATION
                                            .value()
                                            .equals(run.getStatus()));
                    AgentExecutionStreamCheckpoint stream =
                            streamsByExecutionId.get(run.getExecutionId());
                    if (stream != null) {
                        snapshotsBySession.put(
                                conversationSessionId, streamCheckpointView(run, stream));
                    }
                });

        // 同一批查询读取各活跃会话最新 assistant，用消息 ID/version 驱动跨实例全量替换。
        Map<String, ConversationView.MessageView> assistantMessagesBySession =
                new LinkedHashMap<>();
        conversationMessageService
                .findAssistantsByConversationSessionIds(conversationSessionIds)
                .forEach(
                        message ->
                                assistantMessagesBySession.put(
                                        message.getConversationSessionId(),
                                        messageView(message, null)));
        return new SessionStreamRecoveryView(
                immutableEventMap(eventsBySession),
                Map.copyOf(snapshotsBySession),
                Map.copyOf(assistantMessagesBySession),
                Map.copyOf(activeBySession));
    }

    @Transactional(readOnly = true)
    public void requireAccessibleSession(String conversationSessionId) {
        // Resolve through the tenant-filtered persistence service before registering an SSE client.
        requiredSession(conversationSessionId);
    }

    /** 判断会话是否仍需要详情 SSE；查询前先执行租户隔离下的会话鉴权。 */
    @Transactional(readOnly = true)
    public boolean hasActiveExecution(String conversationSessionId) {
        requiredSession(conversationSessionId);
        return agentExecutionService.hasActiveExecution(conversationSessionId);
    }

    @Transactional(readOnly = true)
    public ConversationView.AgentExecutionEventView eventView(AgentExecutionEvent event) {
        // 重新读取已提交的 Agent 执行，确保实时事件与重放事件使用相同公开结构。
        AgentExecution run = agentExecutionService.findByExecutionId(event.getExecutionId());
        if (run == null
                || !event.getConversationSessionId().equals(run.getConversationSessionId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_AGENT_EXECUTION_NOT_FOUND);
        }
        return eventView(event, run);
    }

    /** 将持久执行事件转换为前端可直接幂等归约的轻量更新。 */
    @Transactional(readOnly = true)
    public ConversationView.AgentExecutionUpdateView executionUpdate(AgentExecutionEvent event) {
        AgentExecution run = agentExecutionService.findByExecutionId(event.getExecutionId());
        if (run == null
                || !event.getConversationSessionId().equals(run.getConversationSessionId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_AGENT_EXECUTION_NOT_FOUND);
        }
        List<ConversationView.MessageView> relatedMessages =
                conversationMessageService
                        .findUserVisibleByExecutionId(run.getExecutionId())
                        .stream()
                        .map(message -> messageView(message, null))
                        .toList();
        String objective =
                relatedMessages.stream()
                        .filter(
                                message ->
                                        message.conversationMessageId()
                                                .equals(run.getTriggeringConversationMessageId()))
                        .map(ConversationView.MessageView::content)
                        .findFirst()
                        .orElse(EMPTY_TEXT);
        AgentExecutionStreamCheckpoint stream =
                agentExecutionStreamCheckpointService.findByExecutionId(run.getExecutionId());
        return new ConversationView.AgentExecutionUpdateView(
                run.getConversationSessionId(),
                executionStateView(run, objective, stream),
                eventView(event, run),
                relatedMessages);
    }

    /** 按会话和消息标识返回权威完整消息投影，拒绝跨会话读取。 */
    @Transactional(readOnly = true)
    public ConversationView.MessageView messageViewById(
            String conversationSessionId, String conversationMessageId) {
        requiredSession(conversationSessionId);
        ConversationMessage message =
                conversationMessageService.findByConversationMessageId(conversationMessageId);
        if (message == null || !conversationSessionId.equals(message.getConversationSessionId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_CONVERSATION_SESSION_NOT_FOUND);
        }
        return messageView(message, null);
    }

    public ConversationView submitMessage(
            String conversationSessionId, String content, String idempotencyKey, String requestId) {
        ConversationSession session =
                agentExecutionSubmissionService.submit(
                        conversationSessionId, content, idempotencyKey, requestId);
        return snapshot(session);
    }

    @Transactional(readOnly = true)
    public ConversationView.AgentExecutionView controlView(
            String conversationSessionId, AgentExecution run) {
        if (!conversationSessionId.equals(run.getConversationSessionId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_AGENT_EXECUTION_NOT_FOUND);
        }
        List<ConversationView.MessageView> messageViews =
                conversationMessageService
                        .findUserVisibleByConversationSessionId(conversationSessionId)
                        .stream()
                        .map(message -> messageView(message, null))
                        .toList();
        return executionView(run, messageViews);
    }

    private ConversationSession requiredSession(String conversationSessionId) {
        ConversationSession session = conversationSessionService.findByUuid(conversationSessionId);
        if (session == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_CONVERSATION_SESSION_NOT_FOUND);
        }
        return session;
    }

    private ConversationView snapshot(ConversationSession session) {
        // 先构建不含执行投影的消息视图，供每次 Agent 执行解析触发消息形成 objective。
        List<ConversationMessage> messageEntities =
                conversationMessageService.findUserVisibleByConversationSessionId(
                        session.getConversationSessionId());
        List<ConversationView.MessageView> baseMessageViews =
                messageEntities.stream().map(message -> messageView(message, null)).toList();
        List<AgentExecution> executionEntities =
                agentExecutionService.findByConversationSessionIdOrderByCreatedAtDesc(
                        session.getConversationSessionId());

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
                                                MessageRole.ASSISTANT
                                                                .value()
                                                                .equals(message.getRole())
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
                ConversationSessionType.fromValue(session.getSessionType()),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                session.getVersion(),
                0,
                null,
                messageViews,
                latestAgentExecutionView);
    }

    /** 从已经批量读取的数据组装一个不含详情的会话摘要。 */
    private static ConversationSummaryView summary(
            ConversationSession session, AgentExecution execution) {
        return new ConversationSummaryView(
                session.getConversationSessionId(),
                session.getTitle(),
                session.getTag() == null ? EMPTY_TEXT : session.getTag(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                session.getVersion(),
                execution == null
                        ? SessionExecutionStatus.IDLE
                        : SessionExecutionStatus.fromExecutionStatus(
                                ExecutionStatus.fromValue(execution.getStatus())));
    }

    private ConversationView.MessageView messageView(
            ConversationMessage message, ConversationView.AgentExecutionView run) {
        String author =
                message.getAuthorUserId() == null ? message.getRole() : message.getAuthorUserId();
        return new ConversationView.MessageView(
                message.getConversationMessageId(),
                message.getExecutionId(),
                MessageRole.fromValue(message.getRole()),
                author,
                message.getContent(),
                java.util.Objects.requireNonNullElse(message.getVersion(), 0L),
                message.getThinkingProcess(),
                message.getCreatedAt(),
                List.of(),
                run);
    }

    private ConversationView.AgentExecutionView executionView(
            AgentExecution run, List<ConversationView.MessageView> messageViews) {
        List<ConversationView.AgentExecutionEventView> eventViews =
                agentExecutionEventService.findByExecutionId(run.getExecutionId()).stream()
                        .map(event -> eventView(event, run))
                        .toList();
        AgentExecutionStreamCheckpoint stream =
                agentExecutionStreamCheckpointService.findByExecutionId(run.getExecutionId());
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
                ExecutionStatus.fromValue(run.getStatus()),
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
                                ExecutionPhase.fromValue(stream.getPhase()),
                                stream.getOutputDeltaSequence(),
                                stream.getAnswer()));
    }

    /** 将执行实体及最新回答检查点压缩为 SSE 更新使用的轻量状态。 */
    private static ConversationView.AgentExecutionStateView executionStateView(
            AgentExecution run, String objective, AgentExecutionStreamCheckpoint stream) {
        return new ConversationView.AgentExecutionStateView(
                run.getExecutionId(),
                ExecutionStatus.fromValue(run.getStatus()),
                progress(run.getPhase()),
                objective,
                run.getCancellationRequestedAt(),
                stream == null
                        ? null
                        : new ConversationView.AgentExecutionStreamCheckpointView(
                                stream.getExecutionAttemptNumber(),
                                stream.getModelInvocationSequence(),
                                ExecutionPhase.fromValue(stream.getPhase()),
                                stream.getOutputDeltaSequence(),
                                stream.getAnswer()));
    }

    private ConversationView.AgentExecutionEventView eventView(
            AgentExecutionEvent event, AgentExecution run) {
        return new ConversationView.AgentExecutionEventView(
                event.getExecutionEventId(),
                event.getExecutionEventSequence(),
                ExecutionEventType.fromValue(event.getType()),
                event.getOccurredAt(),
                event.getExecutionId(),
                run.getTriggeringConversationMessageId(),
                ExecutionEventType.QUEUED.value().equals(event.getType())
                        ? EVENT_TITLE_EXECUTION_QUEUED
                        : EVENT_TITLE_EXECUTION_STARTED,
                ExecutionEventType.QUEUED.value().equals(event.getType())
                        ? EVENT_DETAIL_EXECUTION_QUEUED
                        : EVENT_DETAIL_EXECUTION_STARTED,
                null,
                Map.of(),
                Map.of(),
                null,
                null,
                ExecutionStatus.fromValue(run.getStatus()));
    }

    private static int progress(String phase) {
        ExecutionPhase executionPhase;
        try {
            executionPhase = ExecutionPhase.fromValue(phase);
        } catch (IllegalArgumentException ignored) {
            return 0;
        }
        return switch (executionPhase) {
            case QUEUED -> 0;
            case RECEIVED -> 5;
            case PREPARING_CONTEXT -> 15;
            case CONNECTING_MODEL -> 25;
            case ANSWERING -> 70;
            case PERSISTING -> 90;
            case COMPLETED -> 100;
        };
    }

    private static String normalizedTitle(String title) {
        return title == null || title.isBlank() ? DEFAULT_CONVERSATION_TITLE : title.trim();
    }

    private static ExecutionStreamCheckpointView streamCheckpointView(
            AgentExecution run, AgentExecutionStreamCheckpoint stream) {
        return new ExecutionStreamCheckpointView(
                run.getExecutionId(),
                stream.getExecutionAttemptNumber(),
                stream.getModelInvocationSequence(),
                ExecutionPhase.fromValue(stream.getPhase()),
                stream.getOutputDeltaSequence(),
                stream.getAnswer(),
                stream.getCheckpointedAt());
    }

    private static Map<String, List<ConversationView.AgentExecutionUpdateView>> immutableEventMap(
            Map<String, List<ConversationView.AgentExecutionUpdateView>> eventsBySession) {
        Map<String, List<ConversationView.AgentExecutionUpdateView>> result = new HashMap<>();
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
            ExecutionPhase phase,
            long outputDeltaSequence,
            String answer,
            java.time.Instant checkpointedAt) {}

    /** 一次租户级 reconciliation 查询返回的持久事件和最新流投影。 */
    public record SessionStreamRecoveryView(
            Map<String, List<ConversationView.AgentExecutionUpdateView>> eventsBySession,
            Map<String, ExecutionStreamCheckpointView> snapshotsBySession,
            Map<String, ConversationView.MessageView> assistantMessagesBySession,
            Map<String, Boolean> activeBySession) {}

    /** 首次详情流握手使用的权威会话快照及持久事件高水位。 */
    public record SessionStreamBootstrapView(
            /** 当前完整会话投影。 */
            ConversationView session,
            /** 快照已经覆盖的最大持久执行事件序号。 */
            long executionEventSequence) {}
}
