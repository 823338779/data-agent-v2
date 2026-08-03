package com.datagent.agentv2.conversation.stream;

import com.datagent.agentv2.conversation.execution.ExecutionPhase;
import java.time.Duration;
import java.time.Instant;

/** 累计单个 attempt/turn 的流正文，并判断有界 checkpoint 时机。 */
public final class ExecutionStreamAccumulator {

    public static final Duration MAX_CHECKPOINT_INTERVAL = Duration.ofMillis(250);
    public static final int MAX_UNCHECKPOINTED_CHARACTERS = 512;

    private final long executionAttemptNumber;
    private final long modelInvocationSequence;
    private final StringBuilder answer;
    private long outputDeltaSequence;
    private int uncheckpointedCharacters;
    private Instant checkpointedAt;
    private ExecutionPhase phase;

    public ExecutionStreamAccumulator(
            long executionAttemptNumber,
            long modelInvocationSequence,
            long outputDeltaSequence,
            String answer,
            String phase,
            Instant checkpointedAt) {
        this.executionAttemptNumber = executionAttemptNumber;
        this.modelInvocationSequence = modelInvocationSequence;
        this.outputDeltaSequence = outputDeltaSequence;
        this.answer = new StringBuilder(answer);
        this.phase = ExecutionPhase.fromValue(phase);
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
        return uncheckpointedCharacters > 0
                && (uncheckpointedCharacters >= MAX_UNCHECKPOINTED_CHARACTERS
                        || !now.isBefore(checkpointedAt.plus(MAX_CHECKPOINT_INTERVAL)));
    }

    public void checkpointed(Instant now) {
        checkpointedAt = now;
        uncheckpointedCharacters = 0;
    }

    public boolean hasPending() {
        return uncheckpointedCharacters > 0;
    }

    public long executionAttemptNumber() {
        return executionAttemptNumber;
    }

    public long modelInvocationSequence() {
        return modelInvocationSequence;
    }

    public long outputDeltaSequence() {
        return outputDeltaSequence;
    }

    public String answer() {
        return answer.toString();
    }

    public ExecutionPhase phase() {
        return phase;
    }
}
