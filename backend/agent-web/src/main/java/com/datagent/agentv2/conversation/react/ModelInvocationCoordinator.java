package com.datagent.agentv2.conversation.react;

import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ID_PREFIX_EXECUTION_EVENT;

import com.datagent.agentv2.conversation.cluster.AgentExecutionDeltaNotification;
import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.AgentExecutionEvent;
import com.datagent.agentv2.conversation.domain.AgentExecutionStreamCheckpoint;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.ThinkingProcessStatus;
import com.datagent.agentv2.conversation.enums.ExecutionEventType;
import com.datagent.agentv2.conversation.enums.ModelInvocationStatus;
import com.datagent.agentv2.conversation.enums.ToolExecutionStatus;
import com.datagent.agentv2.conversation.exception.ExecutionOwnershipLostException;
import com.datagent.agentv2.conversation.model.ModelOutputDelta;
import com.datagent.agentv2.conversation.process.ConversationProcessCoordinator;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.conversation.stream.ExecutionStreamCheckpointCoordinator;
import com.datagent.agentv2.persistence.entity.ModelInvocationEntity;
import com.datagent.agentv2.persistence.entity.ToolExecutionEntity;
import com.datagent.agentv2.persistence.service.AgentExecutionAttemptService;
import com.datagent.agentv2.persistence.service.AgentExecutionEventService;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.AgentExecutionStreamCheckpointService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.persistence.service.ModelInvocationService;
import com.datagent.agentv2.persistence.service.ToolExecutionService;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 协调模型调用、流式 checkpoint、工具归档和最终回答落库的事务边界。 */
@Service
public class ModelInvocationCoordinator {

    /** 模型调用业务主键使用的统一前缀。 */
    private static final String ID_PREFIX_MODEL_INVOCATION = "model_invocation";

    /** 工具执行业务主键使用的统一前缀。 */
    private static final String ID_PREFIX_TOOL_EXECUTION = "tool_execution";

    /** 维护模型调用序号、状态和恢复输入边界。 */
    private final ModelInvocationService modelInvocations;

    /** 批量保存模型已经声明但尚未执行的工具调用。 */
    private final ToolExecutionService toolExecutions;

    /** 保存内部 assistant 工具消息及最终用户可见回答。 */
    private final ConversationMessageService messages;

    /** 读取会话消息上界并分配消息、执行事件序号。 */
    private final ConversationSessionService sessions;

    /** 锁定执行并完成 owner fenced 的终态更新。 */
    private final AgentExecutionService agentExecutions;

    /** 读取和完成数据库中的权威回答 checkpoint。 */
    private final AgentExecutionStreamCheckpointService persistedStreamCheckpoints;

    /** 管理本地回答累计、checkpoint 阈值和发布序号。 */
    private final ExecutionStreamCheckpointCoordinator streamCheckpointCoordinator;

    /** 将模型 thinking 片段绑定到执行唯一 assistant 中心消息。 */
    private final ConversationProcessCoordinator conversationProcesses;

    /** 在当前事务提交后触发本地与 Redis 回答增量通知。 */
    private final ApplicationEventPublisher applicationEvents;

    /** 保存会话级执行生命周期事件。 */
    private final AgentExecutionEventService events;

    /** 完成当前执行 attempt 的恢复状态记录。 */
    private final AgentExecutionAttemptService attempts;

    /** 为模型调用、消息和事件生成业务标识。 */
    private final IdGenerator ids;

    /** 按 execution/attempt/model invocation 跟踪当前未完成 thinking 节点 UUID。 */
    private final ConcurrentMap<ModelThinkingKey, UUID> activeThinkingItems =
            new ConcurrentHashMap<>();

    /** 装配模型调用事务所需的持久化服务、流协调器和事件发布器。 */
    public ModelInvocationCoordinator(
            ModelInvocationService modelInvocations,
            ToolExecutionService toolExecutions,
            ConversationMessageService messages,
            ConversationSessionService sessions,
            AgentExecutionService agentExecutions,
            AgentExecutionStreamCheckpointService persistedStreamCheckpoints,
            ExecutionStreamCheckpointCoordinator streamCheckpointCoordinator,
            ConversationProcessCoordinator conversationProcesses,
            ApplicationEventPublisher applicationEvents,
            AgentExecutionEventService events,
            AgentExecutionAttemptService attempts,
            IdGenerator ids) {
        this.modelInvocations = modelInvocations;
        this.toolExecutions = toolExecutions;
        this.messages = messages;
        this.sessions = sessions;
        this.agentExecutions = agentExecutions;
        this.persistedStreamCheckpoints = persistedStreamCheckpoints;
        this.streamCheckpointCoordinator = streamCheckpointCoordinator;
        this.conversationProcesses = conversationProcesses;
        this.applicationEvents = applicationEvents;
        this.events = events;
        this.attempts = attempts;
        this.ids = ids;
    }

    /**
     * 使用当前会话消息上界开始一次新的模型调用。
     *
     * @param run 当前 owner 已领取的执行
     * @return 新模型调用的序号与输入边界
     */
    @Transactional
    public ModelInvocationBoundary beginModelInvocation(
            AgentExecutionPort.ClaimedAgentExecution run) {
        return beginModelInvocation(run, null);
    }

    /**
     * 使用显式恢复输入边界开始模型调用，保证重试输入不漂移。
     *
     * @param run 当前 owner 已领取的执行
     * @param recoveredInputBoundary 上次中断调用持久化的消息序号上界
     * @return 新模型调用的序号与输入边界
     */
    @Transactional
    public ModelInvocationBoundary beginModelInvocation(
            AgentExecutionPort.ClaimedAgentExecution run, Long recoveredInputBoundary) {
        return beginModelInvocationLocked(run, recoveredInputBoundary);
    }

    /** 在执行行锁保护下建立模型调用记录，并重置该调用的流 checkpoint。 */
    private ModelInvocationBoundary beginModelInvocationLocked(
            AgentExecutionPort.ClaimedAgentExecution run, Long recoveredInputBoundary) {
        // 锁定执行并校验 owner/attempt，保证后续模型调用记录属于当前领取者。
        AgentExecution owned = lockOwned(run);
        long modelInvocationSequence = modelInvocations.nextSequence(run.executionId());

        // 正常调用读取当前消息上界；恢复调用固定使用旧边界以保持输入确定性。
        long inputUpToConversationMessageSequence =
                recoveredInputBoundary == null
                        ? sessions.findByUuid(owned.getConversationSessionId())
                                .getConversationMessageSequence()
                        : recoveredInputBoundary;
        Instant now = Instant.now();

        // 先持久化 streaming 模型调用，takeover 才能识别并安全重试未完成调用。
        ModelInvocationEntity modelInvocation = new ModelInvocationEntity();
        modelInvocation.setModelInvocationId(ids.next(ID_PREFIX_MODEL_INVOCATION));
        modelInvocation.setExecutionId(run.executionId());
        modelInvocation.setModelInvocationSequence(modelInvocationSequence);
        modelInvocation.setExecutionAttemptNumber(run.executionAttemptNumber());
        modelInvocation.setInputUpToConversationMessageSequence(
                inputUpToConversationMessageSequence);
        modelInvocation.setStatus(ModelInvocationStatus.STREAMING.value());
        modelInvocation.setStartedAt(now);
        modelInvocation.setVersion(0L);
        modelInvocations.save(modelInvocation);

        // 模型调用序号持久化成功后，再清空回答投影并建立对应本地累计器。
        streamCheckpointCoordinator.beginModelInvocation(run, modelInvocationSequence, now);
        return new ModelInvocationBoundary(
                modelInvocationSequence, inputUpToConversationMessageSequence);
    }

    /**
     * 把单个强类型模型增量纳入 thinking 或回答 checkpoint。
     *
     * @param run 当前 owner 已领取的执行
     * @param boundary 当前模型调用边界
     * @param outputDelta 模型返回的非空 thinking/answer 片段
     */
    @Transactional
    public void checkpoint(
            AgentExecutionPort.ClaimedAgentExecution run,
            ModelInvocationBoundary boundary,
            ModelOutputDelta outputDelta) {
        checkpointLocked(run, boundary, outputDelta, Instant.now());
    }

    /** 使用同一时间戳路由 thinking 或完成回答增量累计与通知对象创建。 */
    private void checkpointLocked(
            AgentExecutionPort.ClaimedAgentExecution run,
            ModelInvocationBoundary boundary,
            ModelOutputDelta outputDelta,
            Instant now) {
        if (outputDelta.kind() == ModelOutputDelta.Kind.THINKING) {
            checkpointThinking(run, boundary, outputDelta, now);
            return;
        }

        // 首个答案片段是 thinking 的确定边界，先完成过程节点再发布正文增量。
        finishThinking(run, boundary, ThinkingProcessStatus.SUCCEEDED, now);
        // 协调器先分配输出序号并按阈值落库，再形成可发布的回答增量。
        ExecutionStreamCheckpointCoordinator.AnswerDelta published =
                streamCheckpointCoordinator.appendAnswer(
                        run, boundary.modelInvocationSequence(), outputDelta.content(), now);

        // Spring 事务事件在提交后进入本地 SSE 与 Redis bridge，避免发布未提交状态。
        applicationEvents.publishEvent(AgentExecutionDeltaNotification.from(published));
    }

    /** 首个 thinking 增量创建 UUID，后续增量始终追加到同一节点。 */
    private void checkpointThinking(
            AgentExecutionPort.ClaimedAgentExecution run,
            ModelInvocationBoundary boundary,
            ModelOutputDelta outputDelta,
            Instant now) {
        ModelThinkingKey key = thinkingKey(run, boundary);
        UUID processItemId = activeThinkingItems.get(key);
        if (processItemId == null) {
            UUID created =
                    conversationProcesses.startThinking(
                            run,
                            boundary.modelInvocationSequence(),
                            outputDelta.modelProvider(),
                            outputDelta.modelName(),
                            outputDelta.content(),
                            now);
            afterCommit(() -> activeThinkingItems.put(key, created));
            return;
        }
        conversationProcesses.appendThinking(run, processItemId, outputDelta.content(), now);
    }

    /** provider 中断时把本轮仍开放的 thinking 节点失败收敛，重复调用保持无操作。 */
    @Transactional
    public void failModelOutput(
            AgentExecutionPort.ClaimedAgentExecution run, ModelInvocationBoundary boundary) {
        finishThinking(run, boundary, ThinkingProcessStatus.FAILED, Instant.now());
    }

    /**
     * 归档模型返回的完整工具调用集合。
     *
     * @param run 当前 owner 已领取的执行
     * @param boundary 当前模型调用边界
     * @param calls 已完成解析和校验的工具调用
     */
    @Transactional
    public void archiveToolCalls(
            AgentExecutionPort.ClaimedAgentExecution run,
            ModelInvocationBoundary boundary,
            List<ModelToolCall> calls) {
        archiveToolCallsLocked(run, boundary, calls, Instant.now());
    }

    /** 在一个事务中收敛流、完成模型调用并建立待执行工具记录。 */
    private void archiveToolCallsLocked(
            AgentExecutionPort.ClaimedAgentExecution run,
            ModelInvocationBoundary boundary,
            List<ModelToolCall> calls,
            Instant now) {
        // 工具调用结果是本轮 thinking 的终止边界，必须在归档工具记录前完成节点。
        finishThinking(run, boundary, ThinkingProcessStatus.SUCCEEDED, now);
        // 工具归档前再次执行 fencing，并强制收敛不足 checkpoint 阈值的短增量。
        lockOwned(run);
        streamCheckpointCoordinator.flush(run, boundary.modelInvocationSequence(), now);
        finishModelInvocation(run, boundary, now);

        // 每个 provider call 先追加 PREPARED TOOL 节点，再建立指向中心消息和 UUID 的工具行。
        ConversationMessage assistant = messages.lockAssistantByExecutionId(run.executionId());
        List<ToolExecutionEntity> prepared = new java.util.ArrayList<>();
        for (ModelToolCall call : calls) {
            String toolExecutionId = ids.next(ID_PREFIX_TOOL_EXECUTION);
            UUID processItemId =
                    conversationProcesses.prepareTool(
                            run, boundary.modelInvocationSequence(), call, toolExecutionId, now);
            prepared.add(
                    preparedExecution(
                            run,
                            boundary,
                            assistant.getConversationMessageId(),
                            processItemId,
                            toolExecutionId,
                            call));
        }
        toolExecutions.saveBatch(prepared);
    }

    /**
     * 将最终回答提升为用户消息并完成整个 Agent 执行。
     *
     * @param run 当前 owner 已领取的执行
     * @param boundary 当前模型调用边界
     * @param answer 模型确认完成后的完整回答
     */
    @Transactional
    public void completeFinalAnswer(
            AgentExecutionPort.ClaimedAgentExecution run,
            ModelInvocationBoundary boundary,
            String answer) {
        completeFinalAnswerLocked(run, boundary, answer, Instant.now());
    }

    /** 在同一事务中完成流投影、模型调用、执行 attempt 和生命周期事件。 */
    private void completeFinalAnswerLocked(
            AgentExecutionPort.ClaimedAgentExecution run,
            ModelInvocationBoundary boundary,
            String answer,
            Instant now) {
        // 无 answer delta 的供应商也必须在最终回答边界完成仍开放的 thinking 节点。
        finishThinking(run, boundary, ThinkingProcessStatus.SUCCEEDED, now);
        // 最终回答落库前先锁定执行，并把所有短增量写入后释放本地累计器。
        AgentExecution owned = lockOwned(run);
        streamCheckpointCoordinator.flushAndRelease(run, boundary.modelInvocationSequence(), now);
        finishModelInvocation(run, boundary, now);

        // 使用最新 checkpoint 版本完成投影，拒绝旧 owner 或并发写入覆盖最终回答。
        AgentExecutionStreamCheckpoint stream =
                persistedStreamCheckpoints.findByExecutionId(run.executionId());
        // 完成更新必须同时匹配 checkpoint 版本、owner 和模型调用边界。
        if (!persistedStreamCheckpoints.complete(
                run.executionId(),
                stream.getVersion(),
                run.executionAttemptNumber(),
                boundary.modelInvocationSequence(),
                run.ownerInstanceId(),
                answer,
                now)) {
            throw new ExecutionOwnershipLostException(run.executionId());
        }

        // 回答投影完成后更新预建中心消息正文，不再创建第二条 assistant 消息。
        ConversationMessage assistant = messages.lockAssistantByExecutionId(run.executionId());
        assistant.completeAssistantContent(answer);
        if (!messages.updateById(assistant)) {
            throw new ExecutionOwnershipLostException(run.executionId());
        }

        // 执行终态更新同时释放 lease；失败表示 owner 已经丢失。
        if (!agentExecutions.completeOwned(
                run.executionId(), run.ownerInstanceId(), run.executionAttemptNumber(), now)) {
            throw new ExecutionOwnershipLostException(run.executionId());
        }

        // attempt 和会话事件作为恢复审计信息随执行终态一起提交。
        attempts.finish(
                run.executionId(),
                run.executionAttemptNumber(),
                ModelInvocationStatus.COMPLETED.value(),
                now,
                null);
        events.save(
                AgentExecutionEvent.occurred(
                        ids.next(ID_PREFIX_EXECUTION_EVENT),
                        owned.getConversationSessionId(),
                        run.executionId(),
                        sessions.nextExecutionEventSequence(owned.getConversationSessionId()),
                        ExecutionEventType.DONE.value(),
                        null,
                        now));
    }

    /** 以模型调用自身乐观锁版本完成当前 owner 的 streaming 记录。 */
    private void finishModelInvocation(
            AgentExecutionPort.ClaimedAgentExecution run,
            ModelInvocationBoundary boundary,
            Instant now) {
        // 先读取最新版本，再通过 owner/attempt/version 联合条件完成 fenced 更新。
        ModelInvocationEntity modelInvocation =
                modelInvocations.findModelInvocation(
                        run.executionId(), boundary.modelInvocationSequence());
        // 记录缺失或 fenced 更新失败都说明当前 worker 已不能完成该模型调用。
        if (modelInvocation == null
                || !modelInvocations.finishOwned(
                        run.executionId(),
                        boundary.modelInvocationSequence(),
                        run.executionAttemptNumber(),
                        modelInvocation.getVersion(),
                        ModelInvocationStatus.COMPLETED.value(),
                        now,
                        run.ownerInstanceId())) {
            throw new ExecutionOwnershipLostException(run.executionId());
        }
    }

    /** 将一个模型工具调用映射为尚未执行的持久化记录。 */
    private ToolExecutionEntity preparedExecution(
            AgentExecutionPort.ClaimedAgentExecution run,
            ModelInvocationBoundary boundary,
            String assistantConversationMessageId,
            UUID toolProcessItemId,
            String toolExecutionId,
            ModelToolCall call) {
        // prepared 记录绑定 execution、attempt、model invocation、中心消息和 TOOL UUID。
        ToolExecutionEntity execution = new ToolExecutionEntity();
        execution.setToolExecutionId(toolExecutionId);
        execution.setExecutionId(run.executionId());
        execution.setModelInvocationSequence(boundary.modelInvocationSequence());
        execution.setExecutionAttemptNumber(run.executionAttemptNumber());
        execution.setAssistantConversationMessageId(assistantConversationMessageId);
        execution.setToolProcessItemId(toolProcessItemId);
        execution.setToolCallId(call.toolCallId());
        execution.setToolName(call.name());
        execution.setStatus(ToolExecutionStatus.PREPARED.value());
        execution.setExecutionCount(0);
        execution.setVersion(0L);
        return execution;
    }

    /** 对执行行加锁并校验运行状态、owner 实例及 attempt fencing token。 */
    private AgentExecution lockOwned(AgentExecutionPort.ClaimedAgentExecution run) {
        AgentExecution owned = agentExecutions.lockById(run.executionId());

        // 任一所有权条件不匹配都必须立即停止当前事务，避免旧 worker 写入。
        if (owned == null
                || !"running".equals(owned.getStatus())
                || !run.ownerInstanceId().equals(owned.getOwnerInstanceId())
                || run.executionAttemptNumber() != owned.getExecutionAttemptNumber()) {
            throw new ExecutionOwnershipLostException(run.executionId());
        }
        return owned;
    }

    /** 仅对当前模型调用开放的 UUID 执行一次终态转换。 */
    private void finishThinking(
            AgentExecutionPort.ClaimedAgentExecution run,
            ModelInvocationBoundary boundary,
            ThinkingProcessStatus status,
            Instant now) {
        ModelThinkingKey key = thinkingKey(run, boundary);
        UUID processItemId = activeThinkingItems.get(key);
        if (processItemId == null) {
            return;
        }
        conversationProcesses.finishThinking(run, processItemId, status, now);
        afterCommit(() -> activeThinkingItems.remove(key, processItemId));
    }

    private static ModelThinkingKey thinkingKey(
            AgentExecutionPort.ClaimedAgentExecution run, ModelInvocationBoundary boundary) {
        return new ModelThinkingKey(
                run.executionId(),
                run.executionAttemptNumber(),
                boundary.modelInvocationSequence());
    }

    /** 本地生命周期状态只在事务提交后改变，避免回滚后误判节点已经创建或完成。 */
    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                });
    }

    /** execution、attempt 和模型调用序号共同隔离 active thinking UUID。 */
    private record ModelThinkingKey(
            String executionId, long executionAttemptNumber, long modelInvocationSequence) {}

    /**
     * 固定一次模型调用的身份和输入消息上界。
     *
     * @param modelInvocationSequence 执行内单调递增的模型调用序号
     * @param inputUpToConversationMessageSequence 本次调用允许读取的最大消息序号
     */
    public record ModelInvocationBoundary(
            long modelInvocationSequence, long inputUpToConversationMessageSequence) {}
}
