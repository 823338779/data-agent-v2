package com.datagent.agentv2.conversation.stream;

import com.datagent.agentv2.conversation.cluster.AgentExecutionDeltaNotification;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/** 对单个 SSE 连接缓存跳号后的 delta，直到数据库 checkpoint 覆盖缺口。 */
public final class ExecutionStreamResynchronizer {

    public static final int MAX_BUFFERED_DELTAS = 256;

    private final NavigableMap<Long, AgentExecutionDeltaNotification> buffered = new TreeMap<>();
    private CompositeSseCursor cursor;
    private String executionId;
    private String bufferedExecutionId;
    private Instant gapSince;

    public ExecutionStreamResynchronizer(CompositeSseCursor cursor) {
        this.cursor = cursor;
    }

    public synchronized List<AgentExecutionDeltaNotification> accept(
            AgentExecutionDeltaNotification envelope, Instant receivedAt) {
        // 新 Agent 执行只能从首个 delta 直接切换；否则等待 checkpoint 确认身份，避免拼接迟到消息。
        if (executionId != null && !executionId.equals(envelope.executionId())) {
            if (envelope.outputDeltaSequence() == 1) {
                switchExecution(envelope.executionId());
            } else {
                bufferDifferentExecution(envelope, receivedAt);
                return List.of();
            }
        }
        if (executionId == null) {
            executionId = envelope.executionId();
        }
        int projection =
                compareProjection(
                        envelope.executionAttemptNumber(), envelope.modelInvocationSequence());
        if (projection < 0) {
            return List.of();
        }
        if (projection > 0) {
            buffered.clear();
            cursor =
                    cursor.withStream(
                            envelope.executionAttemptNumber(),
                            envelope.modelInvocationSequence(),
                            0);
            gapSince = null;
        }
        if (envelope.outputDeltaSequence() <= cursor.outputDeltaSequence()) {
            return List.of();
        }
        buffered.putIfAbsent(envelope.outputDeltaSequence(), envelope);
        bufferedExecutionId = envelope.executionId();
        if (envelope.outputDeltaSequence() > cursor.outputDeltaSequence() + 1 && gapSince == null) {
            gapSince = receivedAt;
        }
        return drainContiguous();
    }

    public synchronized List<AgentExecutionDeltaNotification> apply(
            Snapshot snapshot, Instant appliedAt) {
        // checkpoint 是 Agent 执行身份与完整内容的权威来源，可安全切换到新的执行投影。
        if (!snapshot.executionId().equals(executionId)) {
            NavigableMap<Long, AgentExecutionDeltaNotification> pending = new TreeMap<>();
            if (snapshot.executionId().equals(bufferedExecutionId)) {
                pending.putAll(buffered);
            }
            switchExecution(snapshot.executionId());
            buffered.putAll(pending);
        }
        if (bufferedExecutionId != null && !snapshot.executionId().equals(bufferedExecutionId)) {
            buffered.clear();
            bufferedExecutionId = null;
        }
        int projection =
                compareProjection(
                        snapshot.executionAttemptNumber(), snapshot.modelInvocationSequence());
        if (projection < 0) {
            return List.of();
        }
        cursor =
                cursor.withStream(
                        snapshot.executionAttemptNumber(),
                        snapshot.modelInvocationSequence(),
                        snapshot.outputDeltaSequence());
        buffered.headMap(snapshot.outputDeltaSequence(), true).clear();
        bufferedExecutionId = buffered.isEmpty() ? null : snapshot.executionId();
        gapSince = null;
        List<AgentExecutionDeltaNotification> drained = drainContiguous();
        if (!buffered.isEmpty() && buffered.firstKey() > cursor.outputDeltaSequence() + 1) {
            gapSince = appliedAt;
        }
        return drained;
    }

    public synchronized boolean needsSnapshot() {
        return gapSince != null;
    }

    public synchronized boolean canAdvance(Snapshot snapshot) {
        if (executionId == null || !snapshot.executionId().equals(executionId)) {
            return true;
        }
        int projection =
                compareProjection(
                        snapshot.executionAttemptNumber(), snapshot.modelInvocationSequence());
        return projection > 0
                || (projection == 0
                        && snapshot.outputDeltaSequence() > cursor.outputDeltaSequence());
    }

    public synchronized boolean shouldClose(Instant now, Duration leaseWindow) {
        return buffered.size() > MAX_BUFFERED_DELTAS
                || (gapSince != null && !now.isBefore(gapSince.plus(leaseWindow)));
    }

    public synchronized CompositeSseCursor cursor() {
        return cursor;
    }

    private List<AgentExecutionDeltaNotification> drainContiguous() {
        List<AgentExecutionDeltaNotification> ready = new ArrayList<>();
        while (true) {
            long expected = cursor.outputDeltaSequence() + 1;
            AgentExecutionDeltaNotification next = buffered.remove(expected);
            if (next == null) {
                break;
            }
            ready.add(next);
            cursor =
                    cursor.withStream(
                            next.executionAttemptNumber(),
                            next.modelInvocationSequence(),
                            next.outputDeltaSequence());
        }
        if (buffered.isEmpty()) {
            bufferedExecutionId = null;
            gapSince = null;
        }
        return List.copyOf(ready);
    }

    private int compareProjection(long executionAttemptNumber, long modelInvocationSequence) {
        int attempt = Long.compare(executionAttemptNumber, cursor.executionAttemptNumber());
        return attempt != 0
                ? attempt
                : Long.compare(modelInvocationSequence, cursor.modelInvocationSequence());
    }

    private void bufferDifferentExecution(
            AgentExecutionDeltaNotification envelope, Instant receivedAt) {
        if (!envelope.executionId().equals(bufferedExecutionId)) {
            buffered.clear();
            bufferedExecutionId = envelope.executionId();
        }
        buffered.putIfAbsent(envelope.outputDeltaSequence(), envelope);
        if (gapSince == null) {
            gapSince = receivedAt;
        }
    }

    private void switchExecution(String nextExecutionId) {
        executionId = nextExecutionId;
        bufferedExecutionId = null;
        buffered.clear();
        cursor = cursor.withStream(0, 0, 0);
        gapSince = null;
    }

    public record Snapshot(
            String executionId,
            long executionAttemptNumber,
            long modelInvocationSequence,
            long outputDeltaSequence) {}
}
