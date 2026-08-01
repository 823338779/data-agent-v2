package com.datagent.agentv2.conversation.stream;

import com.datagent.agentv2.conversation.model.ModelDelta;
import com.datagent.agentv2.conversation.react.ReactLimitsProperties;
import com.datagent.agentv2.conversation.runtime.RunExecutionPort;
import com.datagent.agentv2.conversation.runtime.RunOwnershipLostException;
import com.datagent.agentv2.persistence.entity.AgentRunStreamEntity;
import com.datagent.agentv2.persistence.entity.AgentRunEntity;
import com.datagent.agentv2.persistence.service.AgentRunService;
import com.datagent.agentv2.persistence.service.AgentRunStreamService;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

/** 管理 owner 本地流累计，并用数据库条件更新建立跨实例 fencing。 */
@Service
public final class RunStreamCheckpointService {

    private final AgentRunStreamService streams;
    private final AgentRunService runs;
    private final ReactLimitsProperties limits;
    private final ConcurrentMap<StreamKey, StreamAccumulator> accumulators =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> sessionIds = new ConcurrentHashMap<>();

    public RunStreamCheckpointService(
            AgentRunStreamService streams, AgentRunService runs, ReactLimitsProperties limits) {
        this.streams = streams;
        this.runs = runs;
        this.limits = limits;
    }

    /** 切换 attempt/turn 时先持久化空投影，并清理该 Run 的旧本地累计。 */
    public void beginTurn(RunExecutionPort.OwnedRun run, long modelTurnSequence, Instant now) {
        AgentRunStreamEntity current = requireStream(run.runId());
        if (!streams.beginTurn(
                run.runId(),
                current.getVersion(),
                run.executionAttempt(),
                modelTurnSequence,
                run.ownerInstanceId(),
                now)) {
            throw new RunOwnershipLostException(run.runId());
        }
        accumulators.keySet().removeIf(key -> key.runId().equals(run.runId()));
        sessionIds.put(run.runId(), requireRun(run.runId()).getSessionId());
        AgentRunStreamEntity reset = requireStream(run.runId());
        accumulators.put(key(run, modelTurnSequence), from(reset, now));
    }

    /** 每个 delta 都获得序号；达到阈值时同步写入完整累计投影。 */
    public PublishedDelta append(
            RunExecutionPort.OwnedRun run,
            long modelTurnSequence,
            ModelDelta delta,
            Instant now) {
        StreamAccumulator accumulator = accumulator(run, modelTurnSequence, now);
        synchronized (accumulator) {
            long sequence = accumulator.append(delta);
            boolean checkpointed = accumulator.shouldCheckpoint(now);
            if (checkpointed) {
                persist(run, accumulator, now);
            }
            return new PublishedDelta(
                    run.tenantId(),
                    sessionId(run.runId()),
                    run.runId(),
                    run.executionAttempt(),
                    modelTurnSequence,
                    sequence,
                    delta.kind(),
                    delta.content(),
                    checkpointed,
                    now);
        }
    }

    /** phase、turn、终态和 lease release 边界强制收敛尚未落库的短增量。 */
    public void flush(
            RunExecutionPort.OwnedRun run,
            long modelTurnSequence,
            Boundary boundary,
            Instant now) {
        Objects.requireNonNull(boundary, "boundary");
        StreamAccumulator accumulator = accumulator(run, modelTurnSequence, now);
        synchronized (accumulator) {
            if (accumulator.hasPending()) {
                persist(run, accumulator, now);
            }
            if (boundary == Boundary.LEASE_RELEASE || boundary == Boundary.TERMINAL) {
                accumulators.remove(key(run, modelTurnSequence), accumulator);
                sessionIds.remove(run.runId());
            }
        }
    }

    /** owner 正常释放 lease 前，强制写入该 attempt 当前 turn 的短增量。 */
    public void flushRun(RunExecutionPort.OwnedRun run, Boundary boundary, Instant now) {
        accumulators.keySet().stream()
                .filter(
                        key ->
                                key.runId().equals(run.runId())
                                        && key.executionAttempt() == run.executionAttempt())
                .toList()
                .forEach(key -> flush(run, key.modelTurnSequence(), boundary, now));
    }

    private StreamAccumulator accumulator(
            RunExecutionPort.OwnedRun run, long modelTurnSequence, Instant now) {
        StreamKey key = key(run, modelTurnSequence);
        return accumulators.computeIfAbsent(
                key,
                ignored -> {
                    AgentRunStreamEntity stream = requireStream(run.runId());
                    if (!Objects.equals(stream.getExecutionAttempt(), run.executionAttempt())
                            || !Objects.equals(
                                    stream.getModelTurnSequence(), modelTurnSequence)) {
                        throw new RunOwnershipLostException(run.runId());
                    }
                    return from(stream, now);
                });
    }

    private void persist(
            RunExecutionPort.OwnedRun run, StreamAccumulator accumulator, Instant now) {
        AgentRunStreamEntity current = requireStream(run.runId());
        Instant reasoningExpiresAt = now.plus(limits.getReasoningRetention());
        if (!streams.checkpoint(
                run.runId(),
                current.getVersion(),
                accumulator.executionAttempt(),
                accumulator.modelTurnSequence(),
                run.ownerInstanceId(),
                accumulator.streamSequence(),
                accumulator.reasoning(),
                accumulator.answer(),
                accumulator.phase(),
                reasoningExpiresAt,
                now)) {
            throw new RunOwnershipLostException(run.runId());
        }
        accumulator.checkpointed(now);
    }

    private AgentRunStreamEntity requireStream(String runId) {
        AgentRunStreamEntity stream = streams.findByRunId(runId);
        if (stream == null) {
            throw new RunOwnershipLostException(runId);
        }
        return stream;
    }

    private String sessionId(String runId) {
        return sessionIds.computeIfAbsent(runId, ignored -> requireRun(runId).getSessionId());
    }

    private AgentRunEntity requireRun(String runId) {
        AgentRunEntity run = runs.findByRunId(runId);
        if (run == null) {
            throw new RunOwnershipLostException(runId);
        }
        return run;
    }

    private static StreamAccumulator from(AgentRunStreamEntity stream, Instant fallback) {
        Instant checkpointedAt =
                stream.getCheckpointedAt() == null ? fallback : stream.getCheckpointedAt();
        return new StreamAccumulator(
                stream.getExecutionAttempt(),
                stream.getModelTurnSequence(),
                stream.getStreamSequence(),
                stream.getReasoning(),
                stream.getAnswer(),
                stream.getPhase(),
                checkpointedAt);
    }

    private static StreamKey key(RunExecutionPort.OwnedRun run, long modelTurnSequence) {
        return new StreamKey(run.runId(), run.executionAttempt(), modelTurnSequence);
    }

    public enum Boundary {
        PHASE,
        TURN,
        TERMINAL,
        LEASE_RELEASE
    }

    public record PublishedDelta(
            String tenantId,
            String sessionId,
            String runId,
            long executionAttempt,
            long modelTurnSequence,
            long streamSequence,
            ModelDelta.Kind kind,
            String delta,
            boolean checkpointed,
            Instant occurredAt) {}

    private record StreamKey(String runId, long executionAttempt, long modelTurnSequence) {}
}
