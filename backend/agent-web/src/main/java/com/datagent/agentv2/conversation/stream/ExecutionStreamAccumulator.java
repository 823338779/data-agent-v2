package com.datagent.agentv2.conversation.stream;

import com.datagent.agentv2.conversation.enums.ExecutionPhase;
import java.time.Duration;
import java.time.Instant;

/** 累计单个 attempt/turn 的流正文，并判断有界 checkpoint 时机。 */
public final class ExecutionStreamAccumulator {

    /** 即使内容很少也必须持久化 checkpoint 的最长时间间隔。 */
    public static final Duration MAX_CHECKPOINT_INTERVAL = Duration.ofMillis(250);

    /** 未持久化回答达到该字符数时立即写 checkpoint。 */
    public static final int MAX_UNCHECKPOINTED_CHARACTERS = 512;

    /** 当前本地累计所属的模型调用序号。 */
    private final long modelInvocationSequence;

    /** 从数据库投影恢复并持续追加的完整回答正文。 */
    private final StringBuilder answer;

    /** 当前模型调用内最后分配给回答增量的发布序号。 */
    private long outputDeltaSequence;

    /** 自上次成功 checkpoint 后累计但尚未落库的字符数。 */
    private int uncheckpointedCharacters;

    /** 最近一次成功写入完整投影的时间。 */
    private Instant checkpointedAt;

    /** 当前回答投影对应的执行阶段。 */
    private ExecutionPhase phase;

    /**
     * 从数据库权威投影恢复一个本地累计器。
     *
     * @param modelInvocationSequence 当前模型调用序号
     * @param outputDeltaSequence 已发布的最后增量序号
     * @param answer 已持久化的完整回答
     * @param phase 已持久化的执行阶段
     * @param checkpointedAt 最近 checkpoint 时间
     */
    public ExecutionStreamAccumulator(
            long modelInvocationSequence,
            long outputDeltaSequence,
            String answer,
            ExecutionPhase phase,
            Instant checkpointedAt) {
        this.modelInvocationSequence = modelInvocationSequence;
        this.outputDeltaSequence = outputDeltaSequence;
        this.answer = new StringBuilder(answer);
        this.phase = java.util.Objects.requireNonNull(phase, "phase");
        this.checkpointedAt = checkpointedAt;
    }

    /** 先分配并记录发布序号，再把正文累计到当前投影。 */
    public long appendAnswer(String answerDelta) {
        outputDeltaSequence++;
        uncheckpointedCharacters += answerDelta.length();
        answer.append(answerDelta);
        phase = ExecutionPhase.ANSWERING;
        return outputDeltaSequence;
    }

    /** 大小和时间阈值采用先到者；空增量不触发无意义写入。 */
    public boolean shouldCheckpoint(Instant now) {
        // 大小或时间任一阈值到达即可收敛；没有待写字符时不制造空更新。
        return uncheckpointedCharacters > 0
                && (uncheckpointedCharacters >= MAX_UNCHECKPOINTED_CHARACTERS
                        || !now.isBefore(checkpointedAt.plus(MAX_CHECKPOINT_INTERVAL)));
    }

    /** 成功持久化完整投影后重置本地 checkpoint 计量窗口。 */
    public void checkpointed(Instant now) {
        checkpointedAt = now;
        uncheckpointedCharacters = 0;
    }

    /** 返回是否存在已经发布但尚未写入完整投影的回答字符。 */
    public boolean hasPending() {
        return uncheckpointedCharacters > 0;
    }

    /** 返回累计器所属的模型调用序号。 */
    public long modelInvocationSequence() {
        return modelInvocationSequence;
    }

    /** 返回当前模型调用内最后发布的回答增量序号。 */
    public long outputDeltaSequence() {
        return outputDeltaSequence;
    }

    /** 返回包含已 checkpoint 和待 checkpoint 内容的完整回答。 */
    public String answer() {
        return answer.toString();
    }

    /** 返回完整回答投影当前对应的执行阶段。 */
    public ExecutionPhase phase() {
        return phase;
    }
}
