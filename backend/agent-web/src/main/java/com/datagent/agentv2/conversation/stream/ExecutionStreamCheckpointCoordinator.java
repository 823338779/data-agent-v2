package com.datagent.agentv2.conversation.stream;

import com.datagent.agentv2.conversation.domain.AgentExecutionStreamCheckpoint;
import com.datagent.agentv2.conversation.runtime.ExecutionStoppedException;
import com.datagent.agentv2.conversation.runtime.LocalAgentExecution;
import com.datagent.agentv2.persistence.service.AgentExecutionStreamCheckpointService;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 协调本机回答累计与数据库检查点。 */
@Service
@RequiredArgsConstructor
public final class ExecutionStreamCheckpointCoordinator {

    private final AgentExecutionStreamCheckpointService streamCheckpoints;
    private final ConcurrentMap<ExecutionStreamKey, ExecutionStreamAccumulator> accumulators =
            new ConcurrentHashMap<>();

    public void beginModelInvocation(LocalAgentExecution run, long sequence, Instant now) {
        AgentExecutionStreamCheckpoint current = requireStream(run.executionId());
        if (!streamCheckpoints.beginModelInvocation(
                run.executionId(), current.getVersion(), sequence)) {
            throw new ExecutionStoppedException(run.executionId());
        }
        accumulators.keySet().removeIf(key -> key.executionId().equals(run.executionId()));
        accumulators.put(key(run, sequence), from(requireStream(run.executionId()), now));
    }

    public AnswerDelta appendAnswer(
            LocalAgentExecution run, long sequence, String answerDelta, Instant now) {
        Objects.requireNonNull(answerDelta, "answerDelta");
        ExecutionStreamAccumulator accumulator = accumulator(run, sequence, now);
        synchronized (accumulator) {
            long outputSequence = accumulator.appendAnswer(answerDelta);
            if (accumulator.shouldCheckpoint(now)) {
                persist(run, accumulator, now);
            }
            return new AnswerDelta(
                    run.tenantId(),
                    run.conversationSessionId(),
                    run.executionId(),
                    sequence,
                    outputSequence,
                    answerDelta,
                    now);
        }
    }

    public void flush(LocalAgentExecution run, long sequence, Instant now) {
        flush(run, sequence, now, false);
    }

    public void flushAndRelease(LocalAgentExecution run, long sequence, Instant now) {
        flush(run, sequence, now, true);
    }

    /** 与数据库终态收敛分离；失败或取消同样必须释放完整回答缓冲。 */
    public void releaseExecution(String executionId) {
        accumulators.keySet().removeIf(key -> key.executionId().equals(executionId));
    }

    private void flush(LocalAgentExecution run, long sequence, Instant now, boolean release) {
        ExecutionStreamAccumulator accumulator = accumulator(run, sequence, now);
        synchronized (accumulator) {
            if (accumulator.hasPending()) {
                persist(run, accumulator, now);
            }
            if (release) {
                accumulators.remove(key(run, sequence), accumulator);
            }
        }
    }

    private ExecutionStreamAccumulator accumulator(
            LocalAgentExecution run, long sequence, Instant now) {
        return accumulators.computeIfAbsent(
                key(run, sequence),
                ignored -> {
                    AgentExecutionStreamCheckpoint stream = requireStream(run.executionId());
                    if (!Objects.equals(stream.getModelInvocationSequence(), sequence)) {
                        throw new ExecutionStoppedException(run.executionId());
                    }
                    return from(stream, now);
                });
    }

    private void persist(
            LocalAgentExecution run, ExecutionStreamAccumulator accumulator, Instant now) {
        AgentExecutionStreamCheckpoint current = requireStream(run.executionId());
        if (!streamCheckpoints.checkpoint(
                run.executionId(),
                current.getVersion(),
                accumulator.modelInvocationSequence(),
                accumulator.outputDeltaSequence(),
                accumulator.answer(),
                accumulator.phase())) {
            throw new ExecutionStoppedException(run.executionId());
        }
        accumulator.checkpointed(now);
    }

    private AgentExecutionStreamCheckpoint requireStream(String executionId) {
        AgentExecutionStreamCheckpoint stream = streamCheckpoints.findByExecutionId(executionId);
        if (stream == null) {
            throw new ExecutionStoppedException(executionId);
        }
        return stream;
    }

    private static ExecutionStreamAccumulator from(
            AgentExecutionStreamCheckpoint stream, Instant now) {
        return new ExecutionStreamAccumulator(
                stream.getModelInvocationSequence(),
                stream.getOutputDeltaSequence(),
                stream.getAnswer(),
                stream.getPhase(),
                now);
    }

    private static ExecutionStreamKey key(LocalAgentExecution run, long sequence) {
        return new ExecutionStreamKey(run.executionId(), sequence);
    }

    public record AnswerDelta(
            String tenantId,
            String conversationSessionId,
            String executionId,
            long modelInvocationSequence,
            long outputDeltaSequence,
            String answerDelta,
            Instant occurredAt) {}

    private record ExecutionStreamKey(String executionId, long modelInvocationSequence) {}
}
