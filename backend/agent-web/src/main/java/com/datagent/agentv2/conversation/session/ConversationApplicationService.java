package com.datagent.agentv2.conversation.session;

import static com.datagent.agentv2.conversation.execution.ExecutionConstants.DEFAULT_CONVERSATION_TITLE;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.EMPTY_TEXT;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_AGENT_EXECUTION_NOT_FOUND;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ERROR_CONVERSATION_SESSION_NOT_FOUND;
import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ID_PREFIX_CONVERSATION_SESSION;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.AgentExecutionEvent;
import com.datagent.agentv2.conversation.domain.AgentExecutionStreamCheckpoint;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.ConversationSession;
import com.datagent.agentv2.conversation.domain.MessageRole;
import com.datagent.agentv2.conversation.domain.MessageUiBlock;
import com.datagent.agentv2.conversation.enums.ExecutionPhase;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import com.datagent.agentv2.conversation.execution.AgentExecutionSubmissionService;
import com.datagent.agentv2.persistence.service.AgentExecutionEventService;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.AgentExecutionStreamCheckpointService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ConversationProjectService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.persistence.service.MessageUiBlockService;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Instant;
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

    private final ConversationSessionService conversationSessionService;
    private final ConversationMessageService conversationMessageService;
    private final AgentExecutionService agentExecutionService;
    private final AgentExecutionEventService agentExecutionEventService;
    private final AgentExecutionStreamCheckpointService agentExecutionStreamCheckpointService;
    private final AgentExecutionSubmissionService agentExecutionSubmissionService;
    private final IdGenerator idGenerator;
    private final MessageUiBlockService messageUiBlockService;
    private final ConversationProjectService projects;

    @Transactional
    public ConversationView create(String title, String tag) {
        return create(title, tag, null);
    }

    /** 项目仅决定新会话归属；先锁项目再保存，避免与项目删除交错。 */
    @Transactional
    public ConversationView create(String title, String tag, String projectId) {
        if (projectId != null) {
            if (projectId.isBlank() || projectId.length() > 64) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid project id");
            }
            if (projects.lockByProjectId(projectId) == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
            }
        }
        ConversationSession session =
                ConversationSession.create(
                        idGenerator.next(ID_PREFIX_CONVERSATION_SESSION),
                        normalizedTitle(title),
                        tag,
                        Instant.now());
        session.assignProject(projectId);
        conversationSessionService.save(session);
        return ConversationView.composeFromNewSession(session);
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
                            return ConversationSummaryView.composeFromSession(session, execution);
                        })
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationView get(String conversationSessionId) {
        return snapshot(requiredSession(conversationSessionId));
    }

    /** 组织操作只返回轻量摘要，不额外读取消息或卡片。 */
    @Transactional(readOnly = true)
    public ConversationSummaryView summary(String sessionId) {
        var session = requiredSession(sessionId);
        var executions =
                agentExecutionService.findByConversationSessionIdsOrderByCreatedAtDesc(
                        List.of(sessionId));
        return ConversationSummaryView.composeFromSession(
                session, executions.isEmpty() ? null : executions.getFirst());
    }

    /** 为首次 SSE 连接读取一次权威会话投影及其持久事件水位。 */
    @Transactional(readOnly = true)
    public SessionStreamBootstrapView streamBootstrap(String conversationSessionId) {
        ConversationSession session = requiredSession(conversationSessionId);
        return SessionStreamBootstrapView.composeFromSession(
                snapshot(session),
                session.getExecutionEventSequence() == null
                        ? 0L
                        : session.getExecutionEventSequence());
    }

    @Transactional(readOnly = true)
    public SessionStreamRecoveryView reconcileStreams(Map<String, Long> afterSequenceBySession) {
        if (afterSequenceBySession.isEmpty()) {
            return SessionStreamRecoveryView.empty();
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
                            conversationSessionId, run.getStatus() == ExecutionStatus.RUNNING);
                    AgentExecutionStreamCheckpoint stream =
                            streamsByExecutionId.get(run.getExecutionId());
                    if (stream != null) {
                        snapshotsBySession.put(
                                conversationSessionId,
                                ExecutionStreamCheckpointView.composeFromStream(run, stream));
                    }
                });

        // 同一批查询读取各活跃会话最新 assistant，用消息 ID/version 驱动跨实例全量替换。
        Map<String, ConversationView.MessageView> assistantMessagesBySession =
                new LinkedHashMap<>();
        List<ConversationMessage> assistantMessages =
                conversationMessageService.findAssistantsByConversationSessionIds(
                        conversationSessionIds);
        assistantMessages.stream()
                .collect(Collectors.groupingBy(ConversationMessage::getConversationSessionId))
                .forEach(
                        (sessionId, sessionMessages) ->
                                composeMessageViews(sessionMessages, Map.of())
                                        .forEach(
                                                message ->
                                                        assistantMessagesBySession.put(
                                                                sessionId, message)));
        return SessionStreamRecoveryView.composeFromReconciliation(
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
                composeMessageViews(
                        conversationMessageService.findByExecutionId(run.getExecutionId()),
                        Map.of());
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
        return ConversationView.AgentExecutionUpdateView.composeFromEvent(
                run, event, objective, stream, relatedMessages);
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
        return composeMessageViews(List.of(message), Map.of()).getFirst();
    }

    public ConversationView submitMessage(
            String conversationSessionId, String content, String idempotencyKey) {
        ConversationSession session =
                agentExecutionSubmissionService.submit(
                        conversationSessionId, content, idempotencyKey);
        return snapshot(session);
    }

    /** 仅本次受理固定工作区，不把默认选择写成会话与分支的一对一关系。 */
    public ConversationView submitMessage(
            String conversationSessionId,
            String content,
            String idempotencyKey,
            String workspaceId) {
        if (workspaceId == null) {
            return submitMessage(conversationSessionId, content, idempotencyKey);
        }
        return snapshot(
                agentExecutionSubmissionService.submit(
                        conversationSessionId, content, idempotencyKey, workspaceId));
    }

    @Transactional(readOnly = true)
    public ConversationView.AgentExecutionView controlView(
            String conversationSessionId, AgentExecution run) {
        if (!conversationSessionId.equals(run.getConversationSessionId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, ERROR_AGENT_EXECUTION_NOT_FOUND);
        }
        List<ConversationView.MessageView> messageViews =
                composeMessageViews(
                        conversationMessageService.findByConversationSessionId(
                                conversationSessionId),
                        Map.of());
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
                conversationMessageService.findByConversationSessionId(
                        session.getConversationSessionId());
        Map<String, List<MessageUiBlockProjection>> blocksByMessage =
                uiBlocksByMessage(messageEntities);
        List<ConversationView.MessageView> baseMessageViews =
                composeMessageViews(messageEntities, Map.of(), blocksByMessage);
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
                composeMessageViews(messageEntities, executionViewsById, blocksByMessage);
        ConversationView.AgentExecutionView latestAgentExecutionView =
                executionEntities.isEmpty()
                        ? null
                        : executionViewsById.get(executionEntities.getFirst().getExecutionId());
        return ConversationView.composeFromSession(session, messageViews, latestAgentExecutionView);
    }

    private ConversationView.AgentExecutionView executionView(
            AgentExecution run, List<ConversationView.MessageView> messageViews) {
        List<ConversationView.AgentExecutionEventView> eventViews =
                agentExecutionEventService.findByExecutionId(run.getExecutionId()).stream()
                        .map(
                                event ->
                                        ConversationView.AgentExecutionEventView.composeFromEvent(
                                                event, run))
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
        return ConversationView.AgentExecutionView.composeFromExecution(
                run, objective, eventViews, stream);
    }

    /** 每批消息只读取一次受控卡片，避免消息数量增长时产生 N+1 查询。 */
    private List<ConversationView.MessageView> composeMessageViews(
            List<ConversationMessage> messages,
            Map<String, ConversationView.AgentExecutionView> executionsById) {
        return composeMessageViews(messages, executionsById, uiBlocksByMessage(messages));
    }

    private Map<String, List<MessageUiBlockProjection>> uiBlocksByMessage(
            List<ConversationMessage> messages) {
        if (messages.isEmpty()) {
            return Map.of();
        }
        return messageUiBlockService.findByMessageIds(messageIds(messages)).stream()
                // 历史数据仍可包含已下线的卡片，只投影当前接口支持的 Git 卡片。
                .filter(block -> "git-change-card".equals(block.type()))
                .collect(
                        Collectors.groupingBy(
                                MessageUiBlock::conversationMessageId,
                                Collectors.mapping(
                                        MessageUiBlockProjection::composeFromBlock,
                                        Collectors.toList())));
    }

    private static List<ConversationView.MessageView> composeMessageViews(
            List<ConversationMessage> messages,
            Map<String, ConversationView.AgentExecutionView> executionsById,
            Map<String, List<MessageUiBlockProjection>> blocksByMessage) {
        return messages.stream()
                .map(
                        message ->
                                ConversationView.MessageView.composeFromMessage(
                                        message,
                                        message.getRole() == MessageRole.ASSISTANT
                                                ? executionsById.get(message.getExecutionId())
                                                : null,
                                        blocksByMessage.getOrDefault(
                                                message.getConversationMessageId(), List.of())))
                .toList();
    }

    private static List<String> messageIds(List<ConversationMessage> messages) {
        return messages.stream().map(ConversationMessage::getConversationMessageId).toList();
    }

    private static String normalizedTitle(String title) {
        return title == null || title.isBlank() ? DEFAULT_CONVERSATION_TITLE : title.trim();
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
            long modelInvocationSequence,
            ExecutionPhase phase,
            long outputDeltaSequence,
            String answer) {

        public static ExecutionStreamCheckpointView composeFromStream(
                AgentExecution execution, AgentExecutionStreamCheckpoint stream) {
            return new ExecutionStreamCheckpointView(
                    execution.getExecutionId(),
                    stream.getModelInvocationSequence(),
                    stream.getPhase(),
                    stream.getOutputDeltaSequence(),
                    stream.getAnswer());
        }
    }

    /** 一次租户级 reconciliation 查询返回的持久事件和最新流投影。 */
    public record SessionStreamRecoveryView(
            Map<String, List<ConversationView.AgentExecutionUpdateView>> eventsBySession,
            Map<String, ExecutionStreamCheckpointView> snapshotsBySession,
            Map<String, ConversationView.MessageView> assistantMessagesBySession,
            Map<String, Boolean> activeBySession) {

        public static SessionStreamRecoveryView empty() {
            return new SessionStreamRecoveryView(Map.of(), Map.of(), Map.of(), Map.of());
        }

        public static SessionStreamRecoveryView composeFromReconciliation(
                Map<String, List<ConversationView.AgentExecutionUpdateView>> eventsBySession,
                Map<String, ExecutionStreamCheckpointView> snapshotsBySession,
                Map<String, ConversationView.MessageView> assistantMessagesBySession,
                Map<String, Boolean> activeBySession) {
            return new SessionStreamRecoveryView(
                    eventsBySession,
                    snapshotsBySession,
                    assistantMessagesBySession,
                    activeBySession);
        }
    }

    /** 首次详情流握手使用的权威会话快照及持久事件高水位。 */
    public record SessionStreamBootstrapView(
            /** 当前完整会话投影。 */
            ConversationView session,
            /** 快照已经覆盖的最大持久执行事件序号。 */
            long executionEventSequence) {

        public static SessionStreamBootstrapView composeFromSession(
                ConversationView session, long executionEventSequence) {
            return new SessionStreamBootstrapView(session, executionEventSequence);
        }
    }
}
