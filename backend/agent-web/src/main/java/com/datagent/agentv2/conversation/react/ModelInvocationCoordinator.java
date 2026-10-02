package com.datagent.agentv2.conversation.react;

import static com.datagent.agentv2.conversation.execution.ExecutionConstants.ID_PREFIX_EXECUTION_EVENT;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.AgentExecutionEvent;
import com.datagent.agentv2.conversation.domain.AgentExecutionStreamCheckpoint;
import com.datagent.agentv2.conversation.domain.ConversationMessage;
import com.datagent.agentv2.conversation.domain.ThinkingProcessStatus;
import com.datagent.agentv2.conversation.enums.ExecutionEventType;
import com.datagent.agentv2.conversation.enums.ExecutionStatus;
import com.datagent.agentv2.conversation.model.api.ModelOutputDelta;
import com.datagent.agentv2.conversation.process.ConversationProcessCoordinator;
import com.datagent.agentv2.conversation.runtime.ExecutionStoppedException;
import com.datagent.agentv2.conversation.runtime.LocalAgentExecution;
import com.datagent.agentv2.conversation.stream.ExecutionStreamCheckpointCoordinator;
import com.datagent.agentv2.conversation.tool.PreparedToolInvocation;
import com.datagent.agentv2.git.GitFinalAnswerComposer;
import com.datagent.agentv2.memory.MemoryReviewMarker;
import com.datagent.agentv2.persistence.service.AgentExecutionEventService;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.AgentExecutionStreamCheckpointService;
import com.datagent.agentv2.persistence.service.ConversationMessageService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
import com.datagent.agentv2.platform.id.IdGenerator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 以 execution、流检查点和 thinking_process 驱动模型调用，不保存恢复专用中间表。 */
@Service
@RequiredArgsConstructor
public class ModelInvocationCoordinator {

    private final ConversationMessageService messages;
    private final ConversationSessionService sessions;
    private final AgentExecutionService executions;
    private final AgentExecutionStreamCheckpointService persistedCheckpoints;
    private final ExecutionStreamCheckpointCoordinator checkpoints;
    private final ConversationProcessCoordinator processes;
    private final ApplicationEventPublisher applicationEvents;
    private final AgentExecutionEventService events;
    private final IdGenerator ids;
    private final ObjectProvider<GitFinalAnswerComposer> gitFinalAnswers;
    private final MemoryReviewMarker memoryReviewMarker;
    private final ConcurrentMap<ModelThinkingKey, UUID> activeThinkingItems =
            new ConcurrentHashMap<>();

    @Transactional
    public ModelInvocationBoundary beginModelInvocation(LocalAgentExecution run) {
        lockRunning(run, Instant.now());
        AgentExecutionStreamCheckpoint current = requireCheckpoint(run.executionId());
        long sequence = current.getModelInvocationSequence() + 1L;
        checkpoints.beginModelInvocation(run, sequence, Instant.now());
        return new ModelInvocationBoundary(sequence, run.inputUpToMessageSequence());
    }

    @Transactional
    public void checkpoint(
            LocalAgentExecution run, ModelInvocationBoundary boundary, ModelOutputDelta delta) {
        Instant now = Instant.now();
        lockRunning(run, now);
        if (delta.kind() == ModelOutputDelta.Kind.THINKING) {
            checkpointThinking(run, boundary, delta, now);
            return;
        }
        finishThinking(run, boundary, ThinkingProcessStatus.SUCCEEDED, now);
        applicationEvents.publishEvent(
                checkpoints.appendAnswer(
                        run, boundary.modelInvocationSequence(), delta.content(), now));
    }

    private void checkpointThinking(
            LocalAgentExecution run,
            ModelInvocationBoundary boundary,
            ModelOutputDelta delta,
            Instant now) {
        ModelThinkingKey key = thinkingKey(run, boundary);
        UUID processItemId = activeThinkingItems.get(key);
        if (processItemId == null) {
            UUID created =
                    processes.startThinking(
                            run,
                            boundary.modelInvocationSequence(),
                            delta.modelProvider(),
                            delta.modelName(),
                            delta.content(),
                            now);
            afterCommit(() -> activeThinkingItems.put(key, created));
            return;
        }
        processes.appendThinking(run, processItemId, delta.content(), now);
    }

    @Transactional
    public void failModelOutput(LocalAgentExecution run, ModelInvocationBoundary boundary) {
        finishThinking(run, boundary, ThinkingProcessStatus.FAILED, Instant.now());
    }

    @Transactional
    public List<PreparedToolInvocation> archiveToolCalls(
            LocalAgentExecution run, ModelInvocationBoundary boundary, List<ModelToolCall> calls) {
        Instant now = Instant.now();
        lockRunning(run, now);
        finishThinking(run, boundary, ThinkingProcessStatus.SUCCEEDED, now);
        checkpoints.flush(run, boundary.modelInvocationSequence(), now);
        return calls.stream()
                .map(
                        call ->
                                new PreparedToolInvocation(
                                        processes.prepareTool(
                                                run, boundary.modelInvocationSequence(), call, now),
                                        call))
                .toList();
    }

    @Transactional
    public void completeFinalAnswer(
            LocalAgentExecution run, ModelInvocationBoundary boundary, String answer) {
        // 完成会更新会话事件序号，必须在 execution 行锁之前锁定会话，与提交/取消保持同序。
        sessions.lockById(run.conversationSessionId());
        Instant now = Instant.now();
        AgentExecution execution = lockRunning(run, now);
        finishThinking(run, boundary, ThinkingProcessStatus.SUCCEEDED, now);
        checkpoints.flushAndRelease(run, boundary.modelInvocationSequence(), now);

        ConversationMessage assistant = messages.lockAssistantByExecutionId(run.executionId());
        // 工具已持久化的 Git 卡片不能被最终模型文字覆盖；同一正文同时写入消息与最终流快照。
        GitFinalAnswerComposer composer = gitFinalAnswers.getIfAvailable();
        if (composer != null) {
            answer = composer.appendRegisteredCards(assistant, answer);
        }
        AgentExecutionStreamCheckpoint stream = requireCheckpoint(run.executionId());
        if (!persistedCheckpoints.complete(
                run.executionId(),
                stream.getVersion(),
                boundary.modelInvocationSequence(),
                answer)) {
            throw new ExecutionStoppedException(run.executionId());
        }
        assistant.completeAssistantContent(answer);
        if (!messages.updateById(assistant)
                || !executions.completeRunning(run.executionId(), execution.getVersion())) {
            throw new ExecutionStoppedException(run.executionId());
        }
        events.save(
                AgentExecutionEvent.occurred(
                        ids.next(ID_PREFIX_EXECUTION_EVENT),
                        run.conversationSessionId(),
                        run.executionId(),
                        sessions.nextExecutionEventSequence(run.conversationSessionId()),
                        ExecutionEventType.DONE,
                        now));
        // 可靠标记与最终正文、DONE 同事务；失败必须向外传播，使完成状态一并回滚。
        memoryReviewMarker.markCompleted(run.executionId());
    }

    private AgentExecution lockRunning(LocalAgentExecution run, Instant now) {
        AgentExecution execution = executions.lockById(run.executionId());
        boolean running =
                execution != null
                        && execution.getStatus() == ExecutionStatus.RUNNING
                        && execution.getHardDeadlineAt() != null
                        && execution.getHardDeadlineAt().isAfter(now);
        if (!running) {
            throw new ExecutionStoppedException(run.executionId());
        }
        return execution;
    }

    private AgentExecutionStreamCheckpoint requireCheckpoint(String executionId) {
        AgentExecutionStreamCheckpoint checkpoint =
                persistedCheckpoints.findByExecutionId(executionId);
        if (checkpoint == null) {
            throw new ExecutionStoppedException(executionId);
        }
        return checkpoint;
    }

    private void finishThinking(
            LocalAgentExecution run,
            ModelInvocationBoundary boundary,
            ThinkingProcessStatus status,
            Instant now) {
        ModelThinkingKey key = thinkingKey(run, boundary);
        UUID processItemId = activeThinkingItems.get(key);
        if (processItemId == null) {
            return;
        }
        processes.finishThinking(run, processItemId, status, now);
        afterCommit(() -> activeThinkingItems.remove(key, processItemId));
    }

    private static ModelThinkingKey thinkingKey(
            LocalAgentExecution run, ModelInvocationBoundary boundary) {
        return new ModelThinkingKey(run.executionId(), boundary.modelInvocationSequence());
    }

    /** worker 退出时释放索引；数据库失败也不能让进程内执行标识永久残留。 */
    public void releaseExecution(String executionId) {
        activeThinkingItems.keySet().removeIf(key -> key.executionId().equals(executionId));
    }

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

    private record ModelThinkingKey(String executionId, long modelInvocationSequence) {}

    public record ModelInvocationBoundary(
            long modelInvocationSequence, long inputUpToConversationMessageSequence) {}
}
