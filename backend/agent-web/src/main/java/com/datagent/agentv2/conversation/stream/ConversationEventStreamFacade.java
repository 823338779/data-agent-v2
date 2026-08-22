package com.datagent.agentv2.conversation.stream;

import com.datagent.agentv2.conversation.cluster.AgentExecutionDeltaNotification;
import com.datagent.agentv2.conversation.enums.ConversationStreamEventType;
import com.datagent.agentv2.conversation.enums.ExecutionPhase;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import com.datagent.agentv2.conversation.process.ConversationMessageChanged;
import com.datagent.agentv2.conversation.runtime.AgentExecutionRuntimeProperties;
import com.datagent.agentv2.conversation.session.ConversationApplicationService;
import com.datagent.agentv2.conversation.session.ConversationView;
import com.datagent.agentv2.persistence.service.AgentExecutionEventPersisted;
import com.datagent.agentv2.platform.context.TenantContext;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 详细会话 SSE：持久事件可重放，瞬时 delta 由 Redis 与 checkpoint 共同收敛。 */
@Service
@RequiredArgsConstructor
public final class ConversationEventStreamFacade {

    /** 单连接异步发送队列允许积压的最大事件数。 */
    private static final int MAX_PENDING_SENDS = 256;

    /** 为每个 SSE 发送线程生成可诊断的单调名称。 */
    private static final AtomicLong CONNECTION_SEQUENCE = new AtomicLong();

    /** 提供会话鉴权、生命周期事件 replay 和数据库流 snapshot。 */
    private final ConversationApplicationService conversations;

    /** 保存本实例连接并接收 owner 本地或 Redis 入站增量。 */
    private final LocalSseRegistry localStreams;

    /** 提供 lease 窗口，用于判定长时间无法收敛的流缺口。 */
    private final AgentExecutionRuntimeProperties runtime;

    /** tenant/session 到当前实例详细 SSE subscriptions 的并发映射。 */
    private final ConcurrentMap<SessionKey, Set<Subscription>> subscriptions =
            new ConcurrentHashMap<>();

    /**
     * 打开一个经过会话鉴权的详细 SSE 连接，并完成权威 snapshot 与实时流握手。
     *
     * @param conversationSessionId 目标会话标识
     * @return 无服务端超时、由连接生命周期主动关闭的 emitter
     */
    public SseEmitter open(String conversationSessionId) {
        // 鉴权必须在注册任何本地/Redis 资源之前完成，避免越权连接短暂接收消息。
        String tenantId = TenantContext.requiredTenantId();
        conversations.requireAccessibleSession(conversationSessionId);

        SseEmitter emitter = new SseEmitter(0L);
        Subscription subscription =
                new Subscription(tenantId, conversationSessionId, emitter, CompositeSseCursor.ZERO);

        // 先建立本地缓冲与 Redis 动态订阅，再读取持久化 replay，消除 subscribe gap。
        LocalSseRegistry.Registration localRegistration =
                localStreams.register(
                        tenantId,
                        conversationSessionId,
                        new LocalSseRegistry.Subscriber() {
                            /** 连续增量直接进入 subscription 初始化缓冲或重同步器。 */
                            @Override
                            public void onDelta(AgentExecutionDeltaNotification envelope) {
                                acceptDelta(subscription, envelope);
                            }

                            /** 跳号增量同样进入重同步器，由 snapshot 决定何时可发送。 */
                            @Override
                            public void onGap(AgentExecutionDeltaNotification envelope) {
                                acceptDelta(subscription, envelope);
                            }
                        });
        subscription.localRegistration = localRegistration;

        // 注册到 facade 映射后，事务事件和定时 reconciliation 才能发现该连接。
        SessionKey key = new SessionKey(tenantId, conversationSessionId);
        subscriptions
                .computeIfAbsent(key, ignored -> new CopyOnWriteArraySet<>())
                .add(subscription);

        // Servlet 的所有关闭路径统一走幂等 remove，释放 registry 和发送线程。
        emitter.onCompletion(() -> remove(subscription));
        emitter.onTimeout(() -> remove(subscription));
        emitter.onError(ignored -> remove(subscription));

        // 每次连接先发送权威会话快照，再归并握手期间到达的增量更新。
        subscription.acceptInitialSnapshot(conversations.streamBootstrap(conversationSessionId));
        subscription.finishInitialization();
        return emitter;
    }

    /** 在执行事件事务提交后，把持久事件实时发送给本实例对应会话连接。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void publish(AgentExecutionEventPersisted saved) {
        // 通知携带租户和领域事件，二者共同组成安全的 subscription 路由键。
        AgentExecutionEventPersisted notification = saved;
        SessionKey key =
                new SessionKey(
                        notification.tenantId(), notification.event().getConversationSessionId());
        Set<Subscription> sessionSubscriptions = subscriptions.get(key);

        // 当前实例没有该会话连接时无需构造公开事件视图。
        if (sessionSubscriptions == null || sessionSubscriptions.isEmpty()) {
            return;
        }
        ConversationView.AgentExecutionUpdateView update =
                conversations.executionUpdate(notification.event());

        // 每个连接自行依据 cursor 去重并处理初始化缓冲。
        sessionSubscriptions.forEach(subscription -> subscription.acceptUpdate(update));
    }

    /** 中心消息事务提交后，把完整消息替换实时投递到本实例同一会话的连接。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void publish(ConversationMessageChanged changed) {
        SessionKey key = new SessionKey(changed.tenantId(), changed.conversationSessionId());
        Set<Subscription> sessionSubscriptions = subscriptions.get(key);
        if (sessionSubscriptions == null || sessionSubscriptions.isEmpty()) {
            return;
        }
        ConversationView.MessageView message =
                conversations.messageViewById(
                        changed.conversationSessionId(), changed.conversationMessageId());
        boolean active = conversations.hasActiveExecution(changed.conversationSessionId());
        sessionSubscriptions.forEach(
                subscription -> {
                    subscription.acceptMessage(message);
                    if (!active) {
                        subscription.completeAfterPending();
                    }
                });
    }

    /** 每秒仅扫描本实例仍有详细连接的会话，补齐持久事件与 checkpoint。 */
    public void reconcileActiveSessions() {
        // 同一轮扫描共用时间，确保所有连接的 gap 超时判断口径一致。
        Instant now = Instant.now();

        // 固定租户的连接共享一次批量 replay/snapshot 查询，避免逐连接访问数据库。
        reconcileTenant(subscriptions, now);
    }

    /** 为同一租户的活跃会话批量查询最小 replay 位置和最新流 snapshot。 */
    private void reconcileTenant(
            Map<SessionKey, Set<Subscription>> tenantSubscriptions, Instant now) {
        // 会话从所有连接的最小事件序号开始 replay，保证最落后的连接也能补齐。
        Map<String, Long> afterSequenceBySession = new HashMap<>();
        tenantSubscriptions.forEach(
                (key, values) ->
                        afterSequenceBySession.put(
                                key.conversationSessionId(),
                                values.stream()
                                        .mapToLong(
                                                subscription ->
                                                        subscription
                                                                .cursor()
                                                                .executionEventSequence())
                                        .min()
                                        .orElse(0L)));

        // 应用服务一次返回事件和 snapshot，避免 N+1 会话查询。
        ConversationApplicationService.SessionStreamRecoveryView reconciliation =
                conversations.reconcileStreams(afterSequenceBySession);

        // 把批量结果分发回每个会话的连接集合。
        tenantSubscriptions.forEach(
                (key, values) ->
                        reconcileSession(
                                values,
                                reconciliation
                                        .eventsBySession()
                                        .getOrDefault(key.conversationSessionId(), List.of()),
                                reconciliation
                                        .snapshotsBySession()
                                        .get(key.conversationSessionId()),
                                reconciliation
                                        .assistantMessagesBySession()
                                        .get(key.conversationSessionId()),
                                reconciliation
                                        .activeBySession()
                                        .getOrDefault(key.conversationSessionId(), false),
                                now));
    }

    /** 使用持久事件和最新 checkpoint 对账一个会话的全部本地连接。 */
    private void reconcileSession(
            Set<Subscription> values,
            List<ConversationView.AgentExecutionUpdateView> events,
            ConversationApplicationService.ExecutionStreamCheckpointView snapshot,
            ConversationView.MessageView assistantMessage,
            boolean active,
            Instant now) {
        // CopyOnWrite set 允许连接关闭与当前遍历并发发生。
        for (Subscription subscription : values) {
            // 持久事件由连接游标去重，因此可向同一会话全部连接投递。
            events.forEach(subscription::acceptUpdate);
            if (assistantMessage != null) {
                subscription.acceptMessage(assistantMessage);
            }
            if (!active) {
                subscription.completeAfterPending();
                continue;
            }

            // snapshot 只在身份、投影、序号或 gap 状态能够推进时发送。
            if (snapshot != null && subscription.needs(snapshot)) {
                sendSnapshot(subscription, snapshot);
            }

            // 缓冲溢出或超过 lease 窗口仍无法补齐时关闭慢/失步连接。
            if (subscription.resynchronizer.shouldClose(now, runtime.leaseDuration())) {
                closeWithError(
                        subscription, new IllegalStateException("SSE stream gap did not converge"));
            }
        }
    }

    /** 将 registry 回调转交给 subscription 的同步增量入口。 */
    private void acceptDelta(Subscription subscription, AgentExecutionDeltaNotification envelope) {
        subscription.acceptDelta(envelope);
    }

    /** 发送权威完整回答 snapshot，并继续发送其后已经连续的缓冲增量。 */
    private void sendSnapshot(
            Subscription subscription,
            ConversationApplicationService.ExecutionStreamCheckpointView snapshot) {
        // snapshot 与并发 delta 共用连接锁，确保发送顺序与复合 cursor 一致。
        synchronized (subscription) {
            // 重同步器只需要 execution、attempt、模型调用和 delta 序号判断投影位置。
            ExecutionStreamResynchronizer.Snapshot position =
                    new ExecutionStreamResynchronizer.Snapshot(
                            snapshot.executionId(),
                            snapshot.executionAttemptNumber(),
                            snapshot.modelInvocationSequence(),
                            snapshot.outputDeltaSequence());
            boolean snapshotIdentityChanged =
                    !snapshot.executionId().equals(subscription.snapshotExecutionId);

            // 同 execution 且不能推进任何坐标时跳过重复数据库 snapshot。
            if (!snapshotIdentityChanged && !subscription.resynchronizer.canAdvance(position)) {
                return;
            }

            // 应用 snapshot 后，重同步器返回所有紧邻新序号的已缓冲增量。
            List<AgentExecutionDeltaNotification> ready =
                    subscription.resynchronizer.apply(position, Instant.now());

            // 连接复合游标推进到数据库已经覆盖的流坐标。
            CompositeSseCursor cursor =
                    subscription
                            .cursor()
                            .withStream(
                                    snapshot.executionAttemptNumber(),
                                    snapshot.modelInvocationSequence(),
                                    snapshot.outputDeltaSequence());
            subscription.cursor = cursor;
            subscription.snapshotExecutionId = snapshot.executionId();

            // 本地 registry 游标同步推进，避免 Redis 回环再次投递 snapshot 已覆盖增量。
            subscription.localRegistration.resume(
                    snapshot.executionId(),
                    snapshot.executionAttemptNumber(),
                    snapshot.modelInvocationSequence(),
                    snapshot.outputDeltaSequence());

            // snapshot 先进入单连接发送队列，随后才排入其后的连续 delta。
            subscription.send(
                    ConversationStreamEventType.SNAPSHOT.value(),
                    new StreamSnapshotEvent(
                            ConversationStreamEventType.SNAPSHOT,
                            subscription.conversationSessionId,
                            snapshot.executionId(),
                            snapshot.executionAttemptNumber(),
                            snapshot.modelInvocationSequence(),
                            snapshot.outputDeltaSequence(),
                            snapshot.phase(),
                            snapshot.answer(),
                            snapshot.checkpointedAt(),
                            Instant.now()));
            ready.forEach(subscription::sendDelta);
        }
    }

    /** 幂等移除 subscription，并释放本地 registry 句柄和专属发送线程。 */
    private void remove(Subscription subscription) {
        // 多个 servlet 生命周期回调可能并发触发，只有首次关闭负责资源释放。
        if (!subscription.closed.compareAndSet(false, true)) {
            return;
        }

        // 从 tenant/session 集合移除连接，末连接时同时移除空映射项。
        subscriptions.computeIfPresent(
                new SessionKey(subscription.tenantId, subscription.conversationSessionId),
                (ignored, values) -> {
                    values.remove(subscription);
                    return values.isEmpty() ? null : values;
                });

        // open 初始化途中也可能失败，因此 localRegistration 允许暂时为空。
        if (subscription.localRegistration != null) {
            subscription.localRegistration.close();
        }

        // 中断尚未发送的任务，防止已关闭 emitter 继续占用线程。
        subscription.sender.shutdownNow();
    }

    /** 清理本地资源后用指定异常完成 SSE emitter。 */
    private void closeWithError(Subscription subscription, RuntimeException failure) {
        remove(subscription);
        // emitter 可能已被 servlet 容器完成，因此错误完成需要容忍重复关闭。
        try {
            // completeWithError 通知 servlet 容器结束异步响应。
            subscription.emitter.completeWithError(failure);
        } catch (IllegalStateException ignored) {
            // Servlet 容器已经完成该连接时无需再次关闭。
        }
    }

    /** 保存单个详细 SSE 连接的游标、初始化缓冲、重同步器和发送队列。 */
    private final class Subscription {

        /** 连接所属租户。 */
        private final String tenantId;

        /** 连接所属会话。 */
        private final String conversationSessionId;

        /** Servlet SSE 响应句柄。 */
        private final SseEmitter emitter;

        /** 连接级回答增量乱序缓冲与 snapshot 对账器。 */
        private ExecutionStreamResynchronizer resynchronizer;

        /** 保证单连接事件按入队顺序串行写入 emitter 的专属执行器。 */
        private final ThreadPoolExecutor sender;

        /** 控制连接资源只释放一次。 */
        private final AtomicBoolean closed = new AtomicBoolean();

        /** 握手期间到达的持久生命周期事件。 */
        private final List<ConversationView.AgentExecutionUpdateView> pendingEvents =
                new ArrayList<>();

        /** 握手期间由本地或 Redis 到达的瞬时回答增量。 */
        private final List<AgentExecutionDeltaNotification> pendingDeltas = new ArrayList<>();

        /** 握手期间到达的中心消息全量替换。 */
        private final List<ConversationView.MessageView> pendingMessages = new ArrayList<>();

        /** 当前连接已发送的每条消息版本，用于本地事件和跨实例对账共同去重。 */
        private final Map<String, Long> messageVersions = new HashMap<>();

        /** 当前连接已经确认的持久事件与流复合游标。 */
        private volatile CompositeSseCursor cursor;

        /** 最近一次发送 snapshot 的 execution，用于识别身份切换。 */
        private volatile String snapshotExecutionId;

        /** 标记 replay/snapshot 握手尚未结束，入站消息需要先缓冲。 */
        private volatile boolean initializing = true;

        /** 当前连接在 LocalSseRegistry 中的注册句柄。 */
        private LocalSseRegistry.Registration localRegistration;

        /** 创建连接状态、重同步器和有界串行发送器。 */
        private Subscription(
                String tenantId,
                String conversationSessionId,
                SseEmitter emitter,
                CompositeSseCursor cursor) {
            this.tenantId = tenantId;
            this.conversationSessionId = conversationSessionId;
            this.emitter = emitter;
            this.cursor = cursor;
            this.resynchronizer = new ExecutionStreamResynchronizer(cursor);
            this.sender = sender();
        }

        /** 接受持久执行更新，并按连接事件游标去重或暂存。 */
        private synchronized void acceptUpdate(ConversationView.AgentExecutionUpdateView update) {
            // 已经 replay/发送过的事件序号不再重复入队。
            if (update.event().executionEventSequence() <= cursor.executionEventSequence()) {
                return;
            }

            // 初始化完成前先缓冲，保证 replay、snapshot 和实时事件顺序可控。
            if (initializing) {
                pendingEvents.add(update);
                return;
            }
            sendUpdate(update);
        }

        /** 仅当消息版本前进时发送完整替换，重复本地事件和 reconciliation 保持幂等。 */
        private synchronized void acceptMessage(ConversationView.MessageView message) {
            long knownVersion = messageVersions.getOrDefault(message.conversationMessageId(), -1L);
            if (message.version() <= knownVersion) {
                return;
            }
            if (initializing) {
                pendingMessages.add(message);
                return;
            }
            sendMessage(message);
        }

        /** 使用首次数据库快照建立复合游标，并只发送一次完整会话状态。 */
        private synchronized void acceptInitialSnapshot(
                ConversationApplicationService.SessionStreamBootstrapView bootstrap) {
            ConversationView.AgentExecutionView execution = bootstrap.session().execution();
            ConversationView.AgentExecutionStreamCheckpointView checkpoint =
                    execution == null ? null : execution.streamCheckpoint();
            CompositeSseCursor bootstrapCursor =
                    checkpoint == null
                            ? new CompositeSseCursor(bootstrap.executionEventSequence(), 0L, 0L, 0L)
                            : new CompositeSseCursor(
                                    bootstrap.executionEventSequence(),
                                    checkpoint.executionAttemptNumber(),
                                    checkpoint.modelInvocationSequence(),
                                    checkpoint.outputDeltaSequence());
            cursor = bootstrapCursor;
            resynchronizer = new ExecutionStreamResynchronizer(bootstrapCursor);
            bootstrap
                    .session()
                    .messages()
                    .forEach(
                            message ->
                                    messageVersions.put(
                                            message.conversationMessageId(), message.version()));
            if (checkpoint != null) {
                snapshotExecutionId = execution.executionId();
                localRegistration.resume(
                        execution.executionId(),
                        checkpoint.executionAttemptNumber(),
                        checkpoint.modelInvocationSequence(),
                        checkpoint.outputDeltaSequence());
            }
            send(
                    ConversationStreamEventType.SESSION_SNAPSHOT.value(),
                    new SessionSnapshotEvent(
                            ConversationStreamEventType.SESSION_SNAPSHOT,
                            conversationSessionId,
                            bootstrap.session(),
                            Instant.now()));
            if (execution == null || isTerminal(execution.status())) {
                completeAfterPending();
            }
        }

        /** 接受回答增量，并在初始化后交给重同步器筛选连续消息。 */
        private synchronized void acceptDelta(AgentExecutionDeltaNotification envelope) {
            // 握手阶段无法确认 snapshot 覆盖位置，因此先限制大小地缓存原始增量。
            if (initializing) {
                pendingDeltas.add(envelope);

                // 初始化缓冲超过上限意味着客户端或 snapshot 无法及时收敛，主动关闭连接。
                if (pendingDeltas.size() > ExecutionStreamResynchronizer.MAX_BUFFERED_DELTAS) {
                    closeWithError(this, new IllegalStateException("SSE initialization overflow"));
                }
                return;
            }

            // 正常阶段只发送与当前游标连续的消息，跳号消息留在重同步器等待 snapshot。
            resynchronizer.accept(envelope, Instant.now()).forEach(this::sendDelta);
        }

        /** 按确定顺序清空握手缓冲，并切换到实时投递状态。 */
        private synchronized void finishInitialization() {
            // 生命周期事件按数据库单调序号发送。
            pendingEvents.stream()
                    .sorted(
                            Comparator.comparingLong(
                                    update -> update.event().executionEventSequence()))
                    .filter(
                            update ->
                                    update.event().executionEventSequence()
                                            > cursor.executionEventSequence())
                    .forEach(this::sendUpdate);

            // 回答增量按 attempt、模型调用、delta 三段坐标排序后交给重同步器。
            pendingDeltas.stream()
                    .sorted(
                            Comparator.comparingLong(
                                            AgentExecutionDeltaNotification::executionAttemptNumber)
                                    .thenComparingLong(
                                            AgentExecutionDeltaNotification
                                                    ::modelInvocationSequence)
                                    .thenComparingLong(
                                            AgentExecutionDeltaNotification::outputDeltaSequence))
                    .flatMap(envelope -> resynchronizer.accept(envelope, Instant.now()).stream())
                    .forEach(this::sendDelta);

            // 初始化快照之后只发送版本更高的并发消息替换。
            pendingMessages.stream()
                    .sorted(Comparator.comparingLong(ConversationView.MessageView::version))
                    .forEach(this::acceptMessageAfterSnapshot);

            // 清空临时引用后再关闭初始化标记，使之后入站消息直接实时处理。
            pendingEvents.clear();
            pendingDeltas.clear();
            pendingMessages.clear();
            initializing = false;
        }

        private void acceptMessageAfterSnapshot(ConversationView.MessageView message) {
            long knownVersion = messageVersions.getOrDefault(message.conversationMessageId(), -1L);
            if (message.version() > knownVersion) {
                sendMessage(message);
            }
        }

        /** 推进持久事件游标并把生命周期事件加入连接发送队列。 */
        private void sendUpdate(ConversationView.AgentExecutionUpdateView update) {
            cursor = cursor.withEvent(update.event().executionEventSequence());
            send(
                    ConversationStreamEventType.EXECUTION_UPDATE.value(),
                    new ExecutionUpdateEvent(
                            ConversationStreamEventType.EXECUTION_UPDATE,
                            update.conversationSessionId(),
                            update.execution(),
                            update.event(),
                            update.messages()));
            if (isTerminal(update.execution().status())) {
                completeAfterPending();
            }
        }

        /** 记录新版本并发送不带补丁语义的完整消息替换。 */
        private void sendMessage(ConversationView.MessageView message) {
            messageVersions.put(message.conversationMessageId(), message.version());
            send(
                    ConversationStreamEventType.MESSAGE_UPDATE.value(),
                    new MessageUpdateEvent(
                            ConversationStreamEventType.MESSAGE_UPDATE,
                            conversationSessionId,
                            message,
                            Instant.now()));
        }

        /** 推进流坐标并把回答增量事件加入连接发送队列。 */
        private void sendDelta(AgentExecutionDeltaNotification envelope) {
            cursor =
                    cursor.withStream(
                            envelope.executionAttemptNumber(),
                            envelope.modelInvocationSequence(),
                            envelope.outputDeltaSequence());
            send(
                    ConversationStreamEventType.ASSISTANT_DELTA.value(),
                    new DeltaEvent(
                            ConversationStreamEventType.ASSISTANT_DELTA,
                            conversationSessionId,
                            envelope.executionId(),
                            envelope.executionAttemptNumber(),
                            envelope.modelInvocationSequence(),
                            envelope.outputDeltaSequence(),
                            envelope.delta(),
                            envelope.occurredAt()));
        }

        /** 在有界单线程队列中异步写 SSE，隔离慢客户端和业务线程。 */
        private void send(String eventName, Object data) {
            // 已关闭连接不再接受新的发送任务。
            if (closed.get()) {
                return;
            }
            // 提交发送任务可能因有界队列满或执行器关闭而被拒绝。
            try {
                // 单线程执行器保持本连接事件数据的入队顺序。
                sender.execute(
                        () -> {
                            // 实际网络写入在连接专属线程中执行，避免阻塞事件发布线程。
                            try {
                                emitter.send(
                                        SseEmitter.event()
                                                .name(eventName)
                                                .data(data, MediaType.APPLICATION_JSON));
                            } catch (IOException | IllegalStateException failure) {
                                // 网络断开或 emitter 已完成属于连接生命周期结束，仅释放本地资源。
                                remove(this);
                            }
                        });
            } catch (RejectedExecutionException failure) {
                // 有界队列已满说明客户端持续过慢，关闭连接让其通过 snapshot 重连。
                closeWithError(this, new IllegalStateException("SSE client is too slow", failure));
            }
        }

        /** 在已经排队的最终事件写出后主动结束终态或空闲会话连接。 */
        private void completeAfterPending() {
            if (closed.get()) {
                return;
            }
            try {
                sender.execute(
                        () -> {
                            try {
                                emitter.complete();
                            } finally {
                                remove(this);
                            }
                        });
            } catch (RejectedExecutionException ignored) {
                remove(this);
            }
        }

        /** 返回连接当前复合游标，供定时 reconciliation 计算最小 replay 位置。 */
        private CompositeSseCursor cursor() {
            return cursor;
        }

        /** 判断数据库 snapshot 是否包含身份变化、缺口修复或更高流坐标。 */
        private boolean needs(
                ConversationApplicationService.ExecutionStreamCheckpointView snapshot) {
            // 使用一次 volatile 读取作为本次比较的一致游标快照。
            CompositeSseCursor current = cursor;
            return !snapshot.executionId().equals(snapshotExecutionId)
                    || resynchronizer.needsSnapshot()
                    || snapshot.executionAttemptNumber() > current.executionAttemptNumber()
                    || (snapshot.executionAttemptNumber() == current.executionAttemptNumber()
                            && snapshot.modelInvocationSequence()
                                    > current.modelInvocationSequence())
                    || (snapshot.executionAttemptNumber() == current.executionAttemptNumber()
                            && snapshot.modelInvocationSequence()
                                    == current.modelInvocationSequence()
                            && snapshot.outputDeltaSequence() > current.outputDeltaSequence());
        }

        /** 为单连接创建有界、单线程、daemon 的 SSE 发送执行器。 */
        private ThreadPoolExecutor sender() {
            // 连接序号只用于线程命名，不参与业务游标。
            long connection = CONNECTION_SEQUENCE.incrementAndGet();
            return new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(MAX_PENDING_SENDS),
                    runnable -> {
                        // daemon 线程避免单个遗留连接阻止 JVM 退出。
                        Thread thread = new Thread(runnable, "session-sse-" + connection);
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
        }
    }

    /**
     * facade 中 subscription 集合的租户隔离键。
     *
     * @param tenantId 租户标识
     * @param conversationSessionId 会话标识
     */
    private record SessionKey(String tenantId, String conversationSessionId) {}

    /** 终态或空闲会话不再占用详情 SSE 连接。 */
    private static boolean isTerminal(ExecutionStatus status) {
        return status == ExecutionStatus.DONE
                || status == ExecutionStatus.CANCELLED
                || status == ExecutionStatus.FAILED
                || status == ExecutionStatus.LOST;
    }

    /** 首次详情流发送的完整会话快照。 */
    private record SessionSnapshotEvent(
            /** 固定为 session_snapshot。 */
            ConversationStreamEventType type,
            /** 快照所属会话。 */
            String conversationSessionId,
            /** 当前数据库权威会话投影。 */
            ConversationView session,
            /** 快照发送时间。 */
            Instant occurredAt) {}

    /** 前端可以直接幂等归约的执行状态、单个时间线事件和相关消息。 */
    private record ExecutionUpdateEvent(
            /** 固定为 execution_update。 */
            ConversationStreamEventType type,
            /** 更新所属会话。 */
            String conversationSessionId,
            /** 当前轻量执行状态。 */
            ConversationView.AgentExecutionStateView execution,
            /** 本次新增的持久时间线事件。 */
            ConversationView.AgentExecutionEventView event,
            /** 当前执行已经产生的用户可见消息。 */
            List<ConversationView.MessageView> messages) {}

    /** assistant 正文及 thinkingProcess 更新使用的完整消息替换事件。 */
    private record MessageUpdateEvent(
            ConversationStreamEventType type,
            String conversationSessionId,
            ConversationView.MessageView message,
            Instant occurredAt) {}

    /**
     * 发送给客户端的瞬时回答增量事件。
     *
     * @param type 固定为 assistant_delta
     * @param conversationSessionId 会话标识
     * @param executionId Agent 执行标识
     * @param executionAttemptNumber owner attempt
     * @param modelInvocationSequence 模型调用序号
     * @param outputDeltaSequence 回答增量序号
     * @param delta 回答正文片段
     * @param occurredAt 增量发生时间
     */
    private record DeltaEvent(
            ConversationStreamEventType type,
            String conversationSessionId,
            String executionId,
            long executionAttemptNumber,
            long modelInvocationSequence,
            long outputDeltaSequence,
            String delta,
            Instant occurredAt) {}

    /**
     * 发送给客户端的数据库权威完整回答 snapshot。
     *
     * @param type 固定为 stream_snapshot
     * @param conversationSessionId 会话标识
     * @param executionId Agent 执行标识
     * @param executionAttemptNumber owner attempt
     * @param modelInvocationSequence 模型调用序号
     * @param outputDeltaSequence snapshot 已覆盖的回答增量序号
     * @param phase 当前流阶段
     * @param answer 完整回答投影
     * @param checkpointedAt 数据库 checkpoint 时间
     * @param occurredAt snapshot 发送时间
     */
    private record StreamSnapshotEvent(
            ConversationStreamEventType type,
            String conversationSessionId,
            String executionId,
            long executionAttemptNumber,
            long modelInvocationSequence,
            long outputDeltaSequence,
            ExecutionPhase phase,
            String answer,
            Instant checkpointedAt,
            Instant occurredAt) {}
}
