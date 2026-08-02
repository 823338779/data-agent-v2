package com.datagent.agentv2.conversation.stream;

import com.datagent.agentv2.conversation.domain.AgentExecution;
import com.datagent.agentv2.conversation.domain.AgentExecutionStreamCheckpoint;
import com.datagent.agentv2.conversation.model.ModelOutputDelta;
import com.datagent.agentv2.conversation.react.ReactLimitsProperties;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.conversation.runtime.ExecutionOwnershipLostException;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.AgentExecutionStreamCheckpointService;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

/**
 * 协调当前执行所有者的本地输出累计与数据库检查点写入。
 *
 * <p>该类型负责运行时 fencing 和检查点时机，不承担通用持久化 CRUD；数据库访问由 {@code AgentExecutionStreamCheckpointService} 提供。
 */
@Service
public final class ExecutionStreamCheckpointCoordinator {

    private final AgentExecutionStreamCheckpointService streamCheckpoints;
    private final AgentExecutionService agentExecutions;
    private final ReactLimitsProperties limits;
    private final ConcurrentMap<ExecutionStreamKey, ExecutionStreamAccumulator> accumulators =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> conversationSessionIds = new ConcurrentHashMap<>();

    public ExecutionStreamCheckpointCoordinator(
            AgentExecutionStreamCheckpointService streamCheckpoints,
            AgentExecutionService agentExecutions,
            ReactLimitsProperties limits) {
        this.streamCheckpoints = streamCheckpoints;
        this.agentExecutions = agentExecutions;
        this.limits = limits;
    }

    /** 切换执行尝试或模型调用时先持久化空投影，并清理该执行的旧本地累计。 */
    public void beginModelInvocation(
            AgentExecutionPort.ClaimedAgentExecution run,
            long modelInvocationSequence,
            Instant now) {
        AgentExecutionStreamCheckpoint current = requireStream(run.executionId());
        if (!streamCheckpoints.beginModelInvocation(
                run.executionId(),
                current.getVersion(),
                run.executionAttemptNumber(),
                modelInvocationSequence,
                run.ownerInstanceId(),
                now)) {
            throw new ExecutionOwnershipLostException(run.executionId());
        }
        accumulators.keySet().removeIf(key -> key.executionId().equals(run.executionId()));
        conversationSessionIds.put(
                run.executionId(), requireExecution(run.executionId()).getConversationSessionId());
        AgentExecutionStreamCheckpoint reset = requireStream(run.executionId());
        accumulators.put(key(run, modelInvocationSequence), from(reset, now));
    }

    /** 每个 delta 都获得序号；达到阈值时同步写入完整累计投影。 */
    public PublishedDelta append(
            AgentExecutionPort.ClaimedAgentExecution run,
            long modelInvocationSequence,
            ModelOutputDelta delta,
            Instant now) {
        ExecutionStreamAccumulator accumulator = accumulator(run, modelInvocationSequence, now);
        synchronized (accumulator) {
            long outputDeltaSequence = accumulator.append(delta);
            boolean checkpointed = accumulator.shouldCheckpoint(now);
            if (checkpointed) {
                persist(run, accumulator, now);
            }
            return new PublishedDelta(
                    run.tenantId(),
                    conversationSessionId(run.executionId()),
                    run.executionId(),
                    run.executionAttemptNumber(),
                    modelInvocationSequence,
                    outputDeltaSequence,
                    delta.kind(),
                    delta.content(),
                    checkpointed,
                    now);
        }
    }

    /** phase、模型调用、终态和 lease release 边界强制收敛尚未落库的短增量。 */
    public void flush(
            AgentExecutionPort.ClaimedAgentExecution run,
            long modelInvocationSequence,
            Boundary boundary,
            Instant now) {
        Objects.requireNonNull(boundary, "boundary");
        ExecutionStreamAccumulator accumulator = accumulator(run, modelInvocationSequence, now);
        synchronized (accumulator) {
            if (accumulator.hasPending()) {
                persist(run, accumulator, now);
            }
            if (boundary == Boundary.LEASE_RELEASE || boundary == Boundary.TERMINAL) {
                accumulators.remove(key(run, modelInvocationSequence), accumulator);
                conversationSessionIds.remove(run.executionId());
            }
        }
    }

    /** owner 正常释放 lease 前，强制写入该执行尝试当前模型调用的短增量。 */
    public void flushExecution(
            AgentExecutionPort.ClaimedAgentExecution run, Boundary boundary, Instant now) {
        accumulators.keySet().stream()
                .filter(
                        key ->
                                key.executionId().equals(run.executionId())
                                        && key.executionAttemptNumber()
                                                == run.executionAttemptNumber())
                .toList()
                .forEach(key -> flush(run, key.modelInvocationSequence(), boundary, now));
    }

    private ExecutionStreamAccumulator accumulator(
            AgentExecutionPort.ClaimedAgentExecution run,
            long modelInvocationSequence,
            Instant now) {
        ExecutionStreamKey key = key(run, modelInvocationSequence);
        return accumulators.computeIfAbsent(
                key,
                ignored -> {
                    AgentExecutionStreamCheckpoint stream = requireStream(run.executionId());
                    if (!Objects.equals(
                                    stream.getExecutionAttemptNumber(),
                                    run.executionAttemptNumber())
                            || !Objects.equals(
                                    stream.getModelInvocationSequence(), modelInvocationSequence)) {
                        throw new ExecutionOwnershipLostException(run.executionId());
                    }
                    return from(stream, now);
                });
    }

    private void persist(
            AgentExecutionPort.ClaimedAgentExecution run,
            ExecutionStreamAccumulator accumulator,
            Instant now) {
        AgentExecutionStreamCheckpoint current = requireStream(run.executionId());
        Instant reasoningExpiresAt = now.plus(limits.getReasoningRetention());
        if (!streamCheckpoints.checkpoint(
                run.executionId(),
                current.getVersion(),
                accumulator.executionAttemptNumber(),
                accumulator.modelInvocationSequence(),
                run.ownerInstanceId(),
                accumulator.outputDeltaSequence(),
                accumulator.reasoning(),
                accumulator.answer(),
                accumulator.phase(),
                reasoningExpiresAt,
                now)) {
            throw new ExecutionOwnershipLostException(run.executionId());
        }
        accumulator.checkpointed(now);
    }

    private AgentExecutionStreamCheckpoint requireStream(String executionId) {
        AgentExecutionStreamCheckpoint stream = streamCheckpoints.findByExecutionId(executionId);
        if (stream == null) {
            throw new ExecutionOwnershipLostException(executionId);
        }
        return stream;
    }

    private String conversationSessionId(String executionId) {
        return conversationSessionIds.computeIfAbsent(
                executionId, ignored -> requireExecution(executionId).getConversationSessionId());
    }

    private AgentExecution requireExecution(String executionId) {
        AgentExecution run = agentExecutions.findByExecutionId(executionId);
        if (run == null) {
            throw new ExecutionOwnershipLostException(executionId);
        }
        return run;
    }

    private static ExecutionStreamAccumulator from(
            AgentExecutionStreamCheckpoint stream, Instant fallback) {
        Instant checkpointedAt =
                stream.getCheckpointedAt() == null ? fallback : stream.getCheckpointedAt();
        return new ExecutionStreamAccumulator(
                stream.getExecutionAttemptNumber(),
                stream.getModelInvocationSequence(),
                stream.getOutputDeltaSequence(),
                stream.getReasoning(),
                stream.getAnswer(),
                stream.getPhase(),
                checkpointedAt);
    }

    private static ExecutionStreamKey key(
            AgentExecutionPort.ClaimedAgentExecution run, long modelInvocationSequence) {
        return new ExecutionStreamKey(
                run.executionId(), run.executionAttemptNumber(), modelInvocationSequence);
    }

    public enum Boundary {
        PHASE,
        MODEL_INVOCATION,
        TERMINAL,
        LEASE_RELEASE
    }

    public record PublishedDelta(
            String tenantId,
            String conversationSessionId,
            String executionId,
            long executionAttemptNumber,
            long modelInvocationSequence,
            long outputDeltaSequence,
            ModelOutputDelta.Kind kind,
            String delta,
            boolean checkpointed,
            Instant occurredAt) {}
}
