package com.datagent.agentv2.conversation.process;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.BoundedProcessPayload;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.ModelThinkingProcessItem;
import com.datagent.agentv2.conversation.domain.ThinkingProcessItem;
import com.datagent.agentv2.conversation.domain.ThinkingProcessStatus;
import com.datagent.agentv2.conversation.domain.ToolThinkingProcessItem;
import com.datagent.agentv2.conversation.exception.ExecutionOwnershipLostException;
import com.datagent.agentv2.conversation.react.ModelToolCall;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.conversation.tool.ToolExecutionResult;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.platform.context.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

/** 以执行 fencing 和 assistant 行锁串行化 ProcessItem 的唯一写入协调器。 */
@Service
@Transactional
public class ConversationProcessCoordinator {

    private static final int THINKING_CHECKPOINT_CHARACTERS = 512;
    private static final long THINKING_CHECKPOINT_MILLIS = 250L;
    private static final String STATUS_RUNNING = "running";
    private static final String ERROR_ASSISTANT_MESSAGE_MISSING =
            "assistant center message is missing for execution: ";
    private static final String ERROR_THINKING_ITEM_REQUIRED =
            "process item is not a thinking item";
    private static final String ERROR_THINKING_ITEM_NOT_RUNNING =
            "thinking process item is not running";
    private static final String ERROR_TOOL_ITEM_REQUIRED = "process item is not a tool item";
    private static final String ERROR_TERMINAL_STATUS_REQUIRED =
            "terminal thinking status is required";

    /** 锁定并更新执行 owner 状态。 */
    private final AgentExecutionService agentExecutions;

    /** 锁定和保存与执行一一对应的 assistant 中心消息。 */
    private final ConversationMessageService messages;

    /** 在任何持久化或审计前清理敏感值。 */
    private final ProcessPayloadSanitizer sanitizer;

    /** 对单节点内容执行 UTF-8 安全限长和摘要计算。 */
    private final ProcessPayloadLimiter limiter;

    /** 提供 thinking、工具入参和工具出参的独立单节点上限。 */
    private final ConversationProcessProperties properties;

    /** 保存成功后发布中心消息更新和提交后审计事件。 */
    private final ApplicationEventPublisher applicationEvents;

    /** 保存当前 owner attempt 的完整安全 thinking，外层列表不做总大小限制。 */
    private final ConcurrentMap<ProcessBufferKey, ThinkingCheckpointBuffer> thinkingBuffers =
            new ConcurrentHashMap<>();

    /** 保存当前 owner attempt 的完整安全工具入参，供后续状态审计关联。 */
    private final ConcurrentMap<ProcessBufferKey, JsonNode> toolInputBuffers =
            new ConcurrentHashMap<>();

    public ConversationProcessCoordinator(
            AgentExecutionService agentExecutions,
            ConversationMessageService messages,
            ProcessPayloadSanitizer sanitizer,
            ProcessPayloadLimiter limiter,
            ConversationProcessProperties properties,
            ApplicationEventPublisher applicationEvents) {
        this.agentExecutions = agentExecutions;
        this.messages = messages;
        this.sanitizer = sanitizer;
        this.limiter = limiter;
        this.properties = properties;
        this.applicationEvents = applicationEvents;
    }

    /** 创建 RUNNING thinking 节点并返回稳定 UUID。 */
    public UUID startThinking(
            AgentExecutionPort.ClaimedAgentExecution run,
            long modelInvocationSequence,
            String provider,
            String model,
            String delta,
            Instant now) {
        Objects.requireNonNull(delta, "delta");
        String fullSafeContent = sanitizer.sanitizeText(delta);

        // 序号必须在 assistant 行锁内读取和分配，避免并发节点产生重复序号。
        UUID processItemId =
                mutate(
                        run,
                        now,
                        assistant -> {
                            UUID itemId = UUID.randomUUID();
                            ProcessPayloadLimiter.BoundedTextResult bounded =
                                    limiter.boundText(
                                            fullSafeContent, properties.maxThinkingBytes());
                            ModelThinkingProcessItem item =
                                    new ModelThinkingProcessItem(
                                            itemId,
                                            assistant.nextProcessSequence(),
                                            ThinkingProcessStatus.RUNNING,
                                            now,
                                            now,
                                            null,
                                            null,
                                            ThinkingProcessItem.CURRENT_SCHEMA_VERSION,
                                            bounded.content(),
                                            bounded.truncated(),
                                            bounded.originalContentSizeBytes(),
                                            bounded.contentSha256(),
                                            modelInvocationSequence,
                                            provider,
                                            model);
                            assistant.appendProcessItem(item);
                            return new Mutation<>(
                                    itemId,
                                    List.of(
                                            new ProcessAuditEvent(
                                                    item, fullSafeContent, null, null)),
                                    true);
                        });
        afterCommit(
                () ->
                        thinkingBuffers.put(
                                key(run, processItemId),
                                new ThinkingCheckpointBuffer(
                                        fullSafeContent, fullSafeContent.length(), now)));
        return processItemId;
    }

    /** 追加 thinking 增量，并以完整安全缓冲重新计算节点持久化边界。 */
    public void appendThinking(
            AgentExecutionPort.ClaimedAgentExecution run,
            UUID processItemId,
            String delta,
            Instant now) {
        Objects.requireNonNull(delta, "delta");
        String safeDelta = sanitizer.sanitizeText(delta);
        ThinkingAppendResult appended =
                mutate(
                        run,
                        now,
                        assistant -> {
                            ModelThinkingProcessItem current =
                                    requireThinking(assistant, processItemId);
                            if (current.status() != ThinkingProcessStatus.RUNNING) {
                                throw new IllegalStateException(ERROR_THINKING_ITEM_NOT_RUNNING);
                            }
                            ThinkingCheckpointBuffer buffer =
                                    thinkingBuffers.getOrDefault(
                                            key(run, processItemId),
                                            new ThinkingCheckpointBuffer(
                                                    current.content(),
                                                    current.content().length(),
                                                    current.createdAt()));
                            String changedFull = buffer.fullSafeContent() + safeDelta;
                            ModelThinkingProcessItem changed =
                                    thinkingItem(
                                            current,
                                            ThinkingProcessStatus.RUNNING,
                                            null,
                                            changedFull);
                            boolean checkpoint = shouldCheckpoint(buffer, changedFull, now);
                            if (checkpoint) {
                                assistant.replaceProcessItem(processItemId, changed);
                            }
                            return new Mutation<>(
                                    new ThinkingAppendResult(
                                            changedFull,
                                            checkpoint
                                                    ? changedFull.length()
                                                    : buffer.checkpointedCharacters(),
                                            checkpoint ? now : buffer.checkpointedAt()),
                                    List.of(
                                            new ProcessAuditEvent(
                                                    changed, changedFull, null, null)),
                                    checkpoint);
                        });
        afterCommit(
                () ->
                        thinkingBuffers.put(
                                key(run, processItemId),
                                new ThinkingCheckpointBuffer(
                                        appended.fullSafeContent(),
                                        appended.checkpointedCharacters(),
                                        appended.checkpointedAt())));
    }

    /** 将 thinking 节点收敛为成功或失败终态，并记录完整安全正文。 */
    public void finishThinking(
            AgentExecutionPort.ClaimedAgentExecution run,
            UUID processItemId,
            ThinkingProcessStatus status,
            Instant now) {
        requireTerminalStatus(status);
        mutate(
                run,
                now,
                assistant -> {
                    ModelThinkingProcessItem current = requireThinking(assistant, processItemId);
                    ThinkingCheckpointBuffer buffer = thinkingBuffers.get(key(run, processItemId));
                    String fullSafeContent =
                            buffer == null ? current.content() : buffer.fullSafeContent();
                    ModelThinkingProcessItem changed =
                            thinkingItem(current, status, now, fullSafeContent);
                    assistant.replaceProcessItem(processItemId, changed);
                    return new Mutation<>(
                            null,
                            List.of(new ProcessAuditEvent(changed, fullSafeContent, null, null)),
                            true);
                });
        afterCommit(() -> thinkingBuffers.remove(key(run, processItemId)));
    }

    /** 创建包含完整调用身份、脱敏入参和模型调用序号的 PREPARED 工具节点。 */
    public UUID prepareTool(
            AgentExecutionPort.ClaimedAgentExecution run,
            long modelInvocationSequence,
            ModelToolCall call,
            String toolExecutionId,
            Instant now) {
        Objects.requireNonNull(call, "call");
        JsonNode fullSafeInput = sanitizer.sanitizeJson(call.arguments());
        BoundedProcessPayload boundedInput =
                limiter.boundJson(fullSafeInput, properties.maxToolInputBytes());

        // 工具节点与 thinking 节点共享同一消息序号空间。
        UUID processItemId =
                mutate(
                        run,
                        now,
                        assistant -> {
                            UUID itemId = UUID.randomUUID();
                            ToolThinkingProcessItem item =
                                    new ToolThinkingProcessItem(
                                            itemId,
                                            assistant.nextProcessSequence(),
                                            ThinkingProcessStatus.PREPARED,
                                            now,
                                            null,
                                            null,
                                            null,
                                            ThinkingProcessItem.CURRENT_SCHEMA_VERSION,
                                            call.name(),
                                            call.name(),
                                            toolExecutionId,
                                            call.toolCallId(),
                                            modelInvocationSequence,
                                            boundedInput,
                                            null,
                                            null,
                                            null,
                                            null);
                            assistant.appendProcessItem(item);
                            return new Mutation<>(
                                    itemId,
                                    List.of(new ProcessAuditEvent(item, null, fullSafeInput, null)),
                                    true);
                        });
        afterCommit(() -> toolInputBuffers.put(key(run, processItemId), fullSafeInput.deepCopy()));
        return processItemId;
    }

    /** 将 PREPARED 工具节点转为 RUNNING 并记录真实开始时间。 */
    public void startTool(
            AgentExecutionPort.ClaimedAgentExecution run, UUID processItemId, Instant now) {
        mutate(
                run,
                now,
                assistant -> {
                    ToolThinkingProcessItem current = requireTool(assistant, processItemId);
                    ToolThinkingProcessItem changed =
                            toolItem(
                                    current,
                                    ThinkingProcessStatus.RUNNING,
                                    now,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null);
                    assistant.replaceProcessItem(processItemId, changed);
                    return new Mutation<>(
                            null,
                            List.of(
                                    new ProcessAuditEvent(
                                            changed,
                                            null,
                                            toolInputBuffers.get(key(run, processItemId)),
                                            null)),
                            true);
                });
    }

    /** 使用完整工具结果构建成功或失败终态，并仅把有界表示写入 JSONB。 */
    public ProcessAuditEvent finishTool(
            AgentExecutionPort.ClaimedAgentExecution run,
            UUID processItemId,
            ToolExecutionResult result,
            Instant now) {
        Objects.requireNonNull(result, "result");
        JsonNode fullSafeOutput =
                result.content() == null ? null : sanitizer.sanitizeJson(result.content());
        BoundedProcessPayload boundedOutput =
                fullSafeOutput == null
                        ? null
                        : limiter.boundJson(fullSafeOutput, properties.maxToolOutputBytes());
        boolean succeeded = result.status() == ToolExecutionResult.Status.SUCCEEDED;
        String safeFailureMessage = sanitizer.sanitizeText(result.failureMessage());

        ProcessAuditEvent auditEvent =
                mutate(
                        run,
                        now,
                        assistant -> {
                            ToolThinkingProcessItem current = requireTool(assistant, processItemId);
                            ThinkingProcessStatus status =
                                    succeeded
                                            ? ThinkingProcessStatus.SUCCEEDED
                                            : ThinkingProcessStatus.FAILED;
                            ToolThinkingProcessItem changed =
                                    toolItem(
                                            current,
                                            status,
                                            current.startedAt(),
                                            now,
                                            boundedOutput,
                                            succeeded,
                                            result.failureCode(),
                                            safeFailureMessage);
                            assistant.replaceProcessItem(processItemId, changed);
                            ProcessAuditEvent event =
                                    new ProcessAuditEvent(
                                            changed,
                                            null,
                                            toolInputBuffers.get(key(run, processItemId)),
                                            fullSafeOutput);
                            return new Mutation<>(event, List.of(event), true);
                        });
        afterCommit(() -> toolInputBuffers.remove(key(run, processItemId)));
        return auditEvent;
    }

    /** 在执行异常边界一次更新中把所有未完成节点收敛为 FAILED。 */
    public void failOpenItems(
            String executionId, String errorCode, String errorMessage, Instant now) {
        AgentExecution execution = requireExecutionLocked(executionId);
        ConversationMessage assistant = requireAssistantLocked(executionId);
        String safeErrorMessage = sanitizer.sanitizeText(errorMessage);
        long attempt = Objects.requireNonNullElse(execution.getExecutionAttemptNumber(), 0L);
        List<ProcessAuditEvent> auditEvents = new ArrayList<>();
        List<ProcessBufferKey> completedKeys = new ArrayList<>();

        // 复制快照后逐项替换，最终仍只保存一次 assistant 消息版本。
        for (ThinkingProcessItem item : List.copyOf(assistant.getThinkingProcess())) {
            if (isTerminal(item.status())) {
                continue;
            }
            ProcessBufferKey bufferKey =
                    new ProcessBufferKey(executionId, attempt, item.processItemId());
            if (item instanceof ModelThinkingProcessItem thinking) {
                ThinkingCheckpointBuffer buffer = thinkingBuffers.get(bufferKey);
                String fullSafeContent =
                        buffer == null ? thinking.content() : buffer.fullSafeContent();
                ModelThinkingProcessItem failed =
                        thinkingItem(thinking, ThinkingProcessStatus.FAILED, now, fullSafeContent);
                assistant.replaceProcessItem(item.processItemId(), failed);
                auditEvents.add(new ProcessAuditEvent(failed, fullSafeContent, null, null));
            } else if (item instanceof ToolThinkingProcessItem tool) {
                ToolThinkingProcessItem failed =
                        toolItem(
                                tool,
                                ThinkingProcessStatus.FAILED,
                                tool.startedAt(),
                                now,
                                null,
                                false,
                                errorCode,
                                safeErrorMessage);
                assistant.replaceProcessItem(item.processItemId(), failed);
                auditEvents.add(
                        new ProcessAuditEvent(failed, null, toolInputBuffers.get(bufferKey), null));
            }
            completedKeys.add(bufferKey);
        }

        // 即使没有开放节点也不制造消息版本；存在变更时一次保存并发布一次通知。
        if (!auditEvents.isEmpty()) {
            saveAndPublish(TenantContext.requiredTenantId(), assistant, auditEvents);
            afterCommit(
                    () -> {
                        completedKeys.forEach(thinkingBuffers::remove);
                        completedKeys.forEach(toolInputBuffers::remove);
                    });
        }
    }

    /** 所有常规变更共享 execution 行锁、owner fencing、assistant 行锁与一次保存路径。 */
    private <T> T mutate(
            AgentExecutionPort.ClaimedAgentExecution run,
            Instant now,
            Function<ConversationMessage, Mutation<T>> mutation) {
        requireOwnedLocked(run, now);
        ConversationMessage assistant = requireAssistantLocked(run.executionId());
        Mutation<T> changed = mutation.apply(assistant);
        if (changed.messageChanged()) {
            saveAndPublish(run.tenantId(), assistant, changed.auditEvents());
        } else {
            // 未达到 checkpoint 阈值时仍按审计要求输出完整安全节点详情。
            changed.auditEvents().forEach(applicationEvents::publishEvent);
        }
        return changed.result();
    }

    /** 保存中心消息后发布稳定变更事件和提交后完整安全审计事件。 */
    private void saveAndPublish(
            String tenantId, ConversationMessage assistant, List<ProcessAuditEvent> auditEvents) {
        if (!messages.updateById(assistant)) {
            throw new ExecutionOwnershipLostException(assistant.getExecutionId());
        }
        applicationEvents.publishEvent(
                new ConversationMessageChanged(
                        tenantId,
                        assistant.getConversationSessionId(),
                        assistant.getConversationMessageId(),
                        assistant.getVersion()));
        auditEvents.forEach(applicationEvents::publishEvent);
    }

    /** 锁定执行并拒绝状态、owner、attempt 或硬截止时间已经失效的写入。 */
    private AgentExecution requireOwnedLocked(
            AgentExecutionPort.ClaimedAgentExecution run, Instant now) {
        AgentExecution execution = requireExecutionLocked(run.executionId());
        boolean owned =
                STATUS_RUNNING.equals(execution.getStatus())
                        && Objects.equals(execution.getOwnerInstanceId(), run.ownerInstanceId())
                        && Objects.equals(
                                execution.getExecutionAttemptNumber(), run.executionAttemptNumber())
                        && execution.getHardDeadlineAt() != null
                        && execution.getHardDeadlineAt().isAfter(now);
        if (!owned) {
            throw new ExecutionOwnershipLostException(run.executionId());
        }
        return execution;
    }

    private AgentExecution requireExecutionLocked(String executionId) {
        AgentExecution execution = agentExecutions.lockById(executionId);
        if (execution == null) {
            throw new ExecutionOwnershipLostException(executionId);
        }
        return execution;
    }

    private ConversationMessage requireAssistantLocked(String executionId) {
        ConversationMessage assistant = messages.lockAssistantByExecutionId(executionId);
        if (assistant == null) {
            throw new IllegalStateException(ERROR_ASSISTANT_MESSAGE_MISSING + executionId);
        }
        return assistant;
    }

    /** 基于完整安全正文重建 thinking 节点，持久化内容始终重新应用单节点边界。 */
    private ModelThinkingProcessItem thinkingItem(
            ModelThinkingProcessItem current,
            ThinkingProcessStatus status,
            Instant completedAt,
            String fullSafeContent) {
        ProcessPayloadLimiter.BoundedTextResult bounded =
                limiter.boundText(fullSafeContent, properties.maxThinkingBytes());
        return new ModelThinkingProcessItem(
                current.processItemId(),
                current.sequence(),
                status,
                current.createdAt(),
                current.startedAt(),
                completedAt,
                completedAt == null ? null : durationMillis(current.startedAt(), completedAt),
                current.schemaVersion(),
                bounded.content(),
                bounded.truncated(),
                bounded.originalContentSizeBytes(),
                bounded.contentSha256(),
                current.modelInvocationSequence(),
                current.modelProvider(),
                current.modelName());
    }

    /** 保留工具身份和输入，仅替换合法生命周期字段与有界结果。 */
    private static ToolThinkingProcessItem toolItem(
            ToolThinkingProcessItem current,
            ThinkingProcessStatus status,
            Instant startedAt,
            Instant completedAt,
            BoundedProcessPayload output,
            Boolean success,
            String errorCode,
            String errorMessage) {
        return new ToolThinkingProcessItem(
                current.processItemId(),
                current.sequence(),
                status,
                current.createdAt(),
                startedAt,
                completedAt,
                completedAt == null || startedAt == null
                        ? null
                        : durationMillis(startedAt, completedAt),
                current.schemaVersion(),
                current.toolId(),
                current.toolName(),
                current.toolExecutionId(),
                current.toolCallId(),
                current.modelInvocationSequence(),
                current.input(),
                output,
                success,
                errorCode,
                errorMessage);
    }

    private static ModelThinkingProcessItem requireThinking(
            ConversationMessage assistant, UUID processItemId) {
        ThinkingProcessItem item = assistant.requireProcessItem(processItemId);
        if (item instanceof ModelThinkingProcessItem thinking) {
            return thinking;
        }
        throw new IllegalStateException(ERROR_THINKING_ITEM_REQUIRED);
    }

    private static ToolThinkingProcessItem requireTool(
            ConversationMessage assistant, UUID processItemId) {
        ThinkingProcessItem item = assistant.requireProcessItem(processItemId);
        if (item instanceof ToolThinkingProcessItem tool) {
            return tool;
        }
        throw new IllegalStateException(ERROR_TOOL_ITEM_REQUIRED);
    }

    private static void requireTerminalStatus(ThinkingProcessStatus status) {
        if (!isTerminal(status)) {
            throw new IllegalArgumentException(ERROR_TERMINAL_STATUS_REQUIRED);
        }
    }

    private static boolean isTerminal(ThinkingProcessStatus status) {
        return status == ThinkingProcessStatus.SUCCEEDED || status == ThinkingProcessStatus.FAILED;
    }

    private static long durationMillis(Instant startedAt, Instant completedAt) {
        return Math.max(0L, Duration.between(startedAt, completedAt).toMillis());
    }

    /** thinking 累计达到 512 字符或距上次落库 250ms 时生成一次消息版本。 */
    private static boolean shouldCheckpoint(
            ThinkingCheckpointBuffer buffer, String changedFull, Instant now) {
        boolean sizeReached =
                changedFull.length() - buffer.checkpointedCharacters()
                        >= THINKING_CHECKPOINT_CHARACTERS;
        boolean timeReached =
                !now.isBefore(buffer.checkpointedAt().plusMillis(THINKING_CHECKPOINT_MILLIS));
        return sizeReached || timeReached;
    }

    private static ProcessBufferKey key(
            AgentExecutionPort.ClaimedAgentExecution run, UUID processItemId) {
        return new ProcessBufferKey(run.executionId(), run.executionAttemptNumber(), processItemId);
    }

    /** 本地缓冲只在事务提交后更新，避免回滚事务留下不可见的脏状态。 */
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

    /** 一次消息变更的返回值以及与该版本绑定的完整安全审计事件。 */
    private record Mutation<T>(
            T result, List<ProcessAuditEvent> auditEvents, boolean messageChanged) {}

    /** thinking append 返回完整安全正文以及更新后的 checkpoint 游标。 */
    private record ThinkingAppendResult(
            String fullSafeContent, int checkpointedCharacters, Instant checkpointedAt) {}

    /** 当前 owner 本地保留的完整安全 thinking 及最近数据库 checkpoint。 */
    private record ThinkingCheckpointBuffer(
            String fullSafeContent, int checkpointedCharacters, Instant checkpointedAt) {}

    /** execution、attempt 和 ProcessItem UUID 共同隔离本地完整安全缓冲。 */
    private record ProcessBufferKey(
            String executionId, long executionAttemptNumber, UUID processItemId) {}
}
