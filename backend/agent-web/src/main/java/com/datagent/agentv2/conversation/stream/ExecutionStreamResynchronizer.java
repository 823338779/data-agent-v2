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

    /** 单连接允许缓存的最大乱序回答增量数量。 */
    public static final int MAX_BUFFERED_DELTAS = 256;

    /** 按 output delta 序号排序的待确认增量缓冲区。 */
    private final NavigableMap<Long, AgentExecutionDeltaNotification> buffered = new TreeMap<>();

    /** 当前 SSE 连接已经确认并发送的复合游标。 */
    private CompositeSseCursor cursor;

    /** 当前权威投影对应的 execution 标识。 */
    private String executionId;

    /** 缓冲区中消息所属的 execution 标识。 */
    private String bufferedExecutionId;

    /** 首次发现连续序号缺口的时间。 */
    private Instant gapSince;

    /** 从当前服务端连接位置创建增量重同步器。 */
    public ExecutionStreamResynchronizer(CompositeSseCursor cursor) {
        this.cursor = cursor;
    }

    /** 接受瞬时回答增量，并返回当前可以连续发送的消息集合。 */
    public synchronized List<AgentExecutionDeltaNotification> accept(
            AgentExecutionDeltaNotification envelope, Instant receivedAt) {
        // 新 Agent 执行只能从首个 delta 直接切换；否则等待 checkpoint 确认身份，避免拼接迟到消息。
        if (executionId != null && !executionId.equals(envelope.executionId())) {
            // 新 execution 的首个增量足以证明新投影起点，否则必须等待数据库确认身份。
            if (envelope.outputDeltaSequence() == 1) {
                switchExecution(envelope.executionId());
            } else {
                bufferDifferentExecution(envelope, receivedAt);
                return List.of();
            }
        }

        // 首条消息建立当前 execution 身份，后续消息必须通过投影比较。
        if (executionId == null) {
            executionId = envelope.executionId();
        }
        int projection =
                compareProjection(
                        envelope.executionAttemptNumber(), envelope.modelInvocationSequence());

        // 旧 owner/旧模型调用的迟到消息不再进入缓冲区。
        if (projection < 0) {
            return List.of();
        }

        // 新 attempt 或模型调用从序号零重新建立连续性窗口。
        if (projection > 0) {
            buffered.clear();
            cursor =
                    cursor.withStream(
                            envelope.executionAttemptNumber(),
                            envelope.modelInvocationSequence(),
                            0);
            gapSince = null;
        }

        // 已被 snapshot 或先前消息覆盖的重复序号直接忽略。
        if (envelope.outputDeltaSequence() <= cursor.outputDeltaSequence()) {
            return List.of();
        }
        buffered.putIfAbsent(envelope.outputDeltaSequence(), envelope);
        bufferedExecutionId = envelope.executionId();

        // 当前最小可用序号不连续时记录缺口起点，供轮询 snapshot 和超时关闭使用。
        if (envelope.outputDeltaSequence() > cursor.outputDeltaSequence() + 1 && gapSince == null) {
            gapSince = receivedAt;
        }
        return drainContiguous();
    }

    /** 应用数据库完整 checkpoint，并返回 snapshot 之后已经连续的缓冲增量。 */
    public synchronized List<AgentExecutionDeltaNotification> apply(
            Snapshot snapshot, Instant appliedAt) {
        // checkpoint 是 Agent 执行身份与完整内容的权威来源，可安全切换到新的执行投影。
        if (!snapshot.executionId().equals(executionId)) {
            // 先保存与新 execution 同源的缓冲消息，切换身份后再恢复这些候选增量。
            NavigableMap<Long, AgentExecutionDeltaNotification> pending = new TreeMap<>();
            // 只有缓冲区与 snapshot 指向同一 execution 时才能跨身份切换保留。
            if (snapshot.executionId().equals(bufferedExecutionId)) {
                pending.putAll(buffered);
            }
            switchExecution(snapshot.executionId());
            buffered.putAll(pending);
        }

        // 与 snapshot execution 不一致的旧缓冲区不能拼接到当前完整回答。
        if (bufferedExecutionId != null && !snapshot.executionId().equals(bufferedExecutionId)) {
            buffered.clear();
            bufferedExecutionId = null;
        }
        int projection =
                compareProjection(
                        snapshot.executionAttemptNumber(), snapshot.modelInvocationSequence());

        // 比当前连接坐标更旧的 snapshot 不具备推进能力。
        if (projection < 0) {
            return List.of();
        }

        // snapshot 覆盖完整正文和已发布序号，因此直接把游标推进到其权威坐标。
        cursor =
                cursor.withStream(
                        snapshot.executionAttemptNumber(),
                        snapshot.modelInvocationSequence(),
                        snapshot.outputDeltaSequence());
        buffered.headMap(snapshot.outputDeltaSequence(), true).clear();
        bufferedExecutionId = buffered.isEmpty() ? null : snapshot.executionId();
        gapSince = null;
        List<AgentExecutionDeltaNotification> drained = drainContiguous();

        // 应用 snapshot 后仍有跳号时，从当前时刻重新计算缺口等待窗口。
        if (!buffered.isEmpty() && buffered.firstKey() > cursor.outputDeltaSequence() + 1) {
            gapSince = appliedAt;
        }
        return drained;
    }

    /** 返回当前连接是否存在需要数据库 snapshot 修复的序号缺口。 */
    public synchronized boolean needsSnapshot() {
        return gapSince != null;
    }

    /** 判断给定 snapshot 是否能推进 execution 身份、投影或 delta 序号。 */
    public synchronized boolean canAdvance(Snapshot snapshot) {
        // 尚未建立身份或 snapshot 指向不同 execution 时允许其建立权威投影。
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

    /** 判断缓冲区是否溢出，或缺口是否超过一个 lease 窗口仍未修复。 */
    public synchronized boolean shouldClose(Instant now, Duration leaseWindow) {
        return buffered.size() > MAX_BUFFERED_DELTAS
                || (gapSince != null && !now.isBefore(gapSince.plus(leaseWindow)));
    }

    /** 返回当前已经确认的 SSE 复合游标。 */
    public synchronized CompositeSseCursor cursor() {
        return cursor;
    }

    /** 从缓冲区头部连续取出紧邻当前游标的回答增量。 */
    private List<AgentExecutionDeltaNotification> drainContiguous() {
        List<AgentExecutionDeltaNotification> ready = new ArrayList<>();

        // 每次只消费 expected=cursor+1，直到遇到第一个缺口。
        while (true) {
            long expected = cursor.outputDeltaSequence() + 1;
            AgentExecutionDeltaNotification next = buffered.remove(expected);
            // 找不到精确相邻序号即停止，保留更高序号等待 snapshot 或缺失消息。
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

        // 缓冲区清空意味着当前不存在待跟踪 execution 或缺口时间。
        if (buffered.isEmpty()) {
            bufferedExecutionId = null;
            gapSince = null;
        }
        return List.copyOf(ready);
    }

    /** 比较给定 attempt/model invocation 与当前游标投影的新旧关系。 */
    private int compareProjection(long executionAttemptNumber, long modelInvocationSequence) {
        int attempt = Long.compare(executionAttemptNumber, cursor.executionAttemptNumber());
        return attempt != 0
                ? attempt
                : Long.compare(modelInvocationSequence, cursor.modelInvocationSequence());
    }

    /** 缓冲尚未被 checkpoint 确认的新 execution 消息。 */
    private void bufferDifferentExecution(
            AgentExecutionDeltaNotification envelope, Instant receivedAt) {
        // 缓冲区一次只服务一个候选 execution，新的候选会替换旧候选。
        if (!envelope.executionId().equals(bufferedExecutionId)) {
            buffered.clear();
            bufferedExecutionId = envelope.executionId();
        }
        buffered.putIfAbsent(envelope.outputDeltaSequence(), envelope);

        // 首条不可直接应用的候选消息启动缺口计时。
        if (gapSince == null) {
            gapSince = receivedAt;
        }
    }

    /** 清空旧投影状态并把连接切换到新的 execution 起点。 */
    private void switchExecution(String nextExecutionId) {
        executionId = nextExecutionId;
        bufferedExecutionId = null;
        buffered.clear();
        cursor = cursor.withStream(0, 0, 0);
        gapSince = null;
    }

    /**
     * 数据库完整流投影中用于重同步的最小坐标。
     *
     * @param executionId snapshot 所属执行
     * @param executionAttemptNumber owner attempt
     * @param modelInvocationSequence 模型调用序号
     * @param outputDeltaSequence snapshot 已覆盖的回答增量序号
     */
    public record Snapshot(
            String executionId,
            long executionAttemptNumber,
            long modelInvocationSequence,
            long outputDeltaSequence) {}
}
