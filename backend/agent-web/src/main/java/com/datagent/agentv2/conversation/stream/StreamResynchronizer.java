package com.datagent.agentv2.conversation.stream;

import com.datagent.agentv2.conversation.cluster.StreamDeltaEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/** 对单个 SSE 连接缓存跳号后的 delta，直到数据库 checkpoint 覆盖缺口。 */
public final class StreamResynchronizer {

    public static final int MAX_BUFFERED_DELTAS = 256;

    private final NavigableMap<Long, StreamDeltaEnvelope> buffered = new TreeMap<>();
    private CompositeSseCursor cursor;
    private String runId;
    private String bufferedRunId;
    private Instant gapSince;

    public StreamResynchronizer(CompositeSseCursor cursor) {
        this.cursor = cursor;
    }

    public synchronized List<StreamDeltaEnvelope> accept(
            StreamDeltaEnvelope envelope, Instant receivedAt) {
        // 新 Run 只能从首个 delta 直接切换；否则等待 checkpoint 确认身份，避免拼接迟到消息。
        if (runId != null && !runId.equals(envelope.runId())) {
            if (envelope.streamSequence() == 1) {
                switchRun(envelope.runId());
            } else {
                bufferDifferentRun(envelope, receivedAt);
                return List.of();
            }
        }
        if (runId == null) {
            runId = envelope.runId();
        }
        int projection =
                compareProjection(envelope.executionAttempt(), envelope.modelTurnSequence());
        if (projection < 0) {
            return List.of();
        }
        if (projection > 0) {
            buffered.clear();
            cursor =
                    cursor.withStream(envelope.executionAttempt(), envelope.modelTurnSequence(), 0);
            gapSince = null;
        }
        if (envelope.streamSequence() <= cursor.streamSequence()) {
            return List.of();
        }
        buffered.putIfAbsent(envelope.streamSequence(), envelope);
        bufferedRunId = envelope.runId();
        if (envelope.streamSequence() > cursor.streamSequence() + 1 && gapSince == null) {
            gapSince = receivedAt;
        }
        return drainContiguous();
    }

    public synchronized List<StreamDeltaEnvelope> apply(Snapshot snapshot, Instant appliedAt) {
        // checkpoint 是 Run 身份与完整内容的权威来源，可安全切换到新的 Run 投影。
        if (!snapshot.runId().equals(runId)) {
            NavigableMap<Long, StreamDeltaEnvelope> pending = new TreeMap<>();
            if (snapshot.runId().equals(bufferedRunId)) {
                pending.putAll(buffered);
            }
            switchRun(snapshot.runId());
            buffered.putAll(pending);
        }
        if (bufferedRunId != null && !snapshot.runId().equals(bufferedRunId)) {
            buffered.clear();
            bufferedRunId = null;
        }
        int projection =
                compareProjection(snapshot.executionAttempt(), snapshot.modelTurnSequence());
        if (projection < 0) {
            return List.of();
        }
        cursor =
                cursor.withStream(
                        snapshot.executionAttempt(),
                        snapshot.modelTurnSequence(),
                        snapshot.streamSequence());
        buffered.headMap(snapshot.streamSequence(), true).clear();
        bufferedRunId = buffered.isEmpty() ? null : snapshot.runId();
        gapSince = null;
        List<StreamDeltaEnvelope> drained = drainContiguous();
        if (!buffered.isEmpty() && buffered.firstKey() > cursor.streamSequence() + 1) {
            gapSince = appliedAt;
        }
        return drained;
    }

    public synchronized boolean needsSnapshot() {
        return gapSince != null;
    }

    public synchronized boolean canAdvance(Snapshot snapshot) {
        if (runId == null || !snapshot.runId().equals(runId)) {
            return true;
        }
        int projection =
                compareProjection(snapshot.executionAttempt(), snapshot.modelTurnSequence());
        return projection > 0
                || (projection == 0 && snapshot.streamSequence() > cursor.streamSequence());
    }

    public synchronized boolean shouldClose(Instant now, Duration leaseWindow) {
        return buffered.size() > MAX_BUFFERED_DELTAS
                || (gapSince != null && !now.isBefore(gapSince.plus(leaseWindow)));
    }

    public synchronized CompositeSseCursor cursor() {
        return cursor;
    }

    private List<StreamDeltaEnvelope> drainContiguous() {
        List<StreamDeltaEnvelope> ready = new ArrayList<>();
        while (true) {
            long expected = cursor.streamSequence() + 1;
            StreamDeltaEnvelope next = buffered.remove(expected);
            if (next == null) {
                break;
            }
            ready.add(next);
            cursor =
                    cursor.withStream(
                            next.executionAttempt(),
                            next.modelTurnSequence(),
                            next.streamSequence());
        }
        if (buffered.isEmpty()) {
            bufferedRunId = null;
            gapSince = null;
        }
        return List.copyOf(ready);
    }

    private int compareProjection(long executionAttempt, long modelTurnSequence) {
        int attempt = Long.compare(executionAttempt, cursor.executionAttempt());
        return attempt != 0 ? attempt : Long.compare(modelTurnSequence, cursor.modelTurnSequence());
    }

    private void bufferDifferentRun(StreamDeltaEnvelope envelope, Instant receivedAt) {
        if (!envelope.runId().equals(bufferedRunId)) {
            buffered.clear();
            bufferedRunId = envelope.runId();
        }
        buffered.putIfAbsent(envelope.streamSequence(), envelope);
        if (gapSince == null) {
            gapSince = receivedAt;
        }
    }

    private void switchRun(String nextRunId) {
        runId = nextRunId;
        bufferedRunId = null;
        buffered.clear();
        cursor = cursor.withStream(0, 0, 0);
        gapSince = null;
    }

    public record Snapshot(
            String runId, long executionAttempt, long modelTurnSequence, long streamSequence) {}
}
