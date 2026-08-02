package com.datagent.agentv2.conversation.stream;

import com.datagent.agentv2.conversation.execution.ExecutionPhase;
import com.datagent.agentv2.conversation.model.ModelOutputDelta;
import java.time.Duration;
import java.time.Instant;

/** 累计单个 attempt/turn 的流正文，并判断有界 checkpoint 时机。 */
public final class ExecutionStreamAccumulator {

    public static final Duration MAX_CHECKPOINT_INTERVAL = Duration.ofMillis(250);
    public static final int MAX_UNCHECKPOINTED_CHARACTERS = 512;

    private final long executionAttemptNumber;
    private final long modelInvocationSequence;
    private final StringBuilder reasoning;
    private final StringBuilder answer;
    private long outputDeltaSequence;
    private int uncheckpointedCharacters;
    private boolean phaseBoundaryReached;
    private Instant checkpointedAt;
    private ExecutionPhase phase;

    public ExecutionStreamAccumulator(
            long executionAttemptNumber,
            long modelInvocationSequence,
            long outputDeltaSequence,
            String reasoning,
            String answer,
            String phase,
            Instant checkpointedAt) {
        this.executionAttemptNumber = executionAttemptNumber;
        this.modelInvocationSequence = modelInvocationSequence;
        this.outputDeltaSequence = outputDeltaSequence;
        this.reasoning = new StringBuilder(reasoning);
        this.answer = new StringBuilder(answer);
        this.phase = ExecutionPhase.fromValue(phase);
        this.checkpointedAt = checkpointedAt;
    }

    /** 先分配并记录发布序号，再把正文累计到当前投影。 */
    public long append(ModelOutputDelta delta) {
        outputDeltaSequence++;
        uncheckpointedCharacters += delta.content().length();
        ExecutionPhase nextPhase =
                delta.kind() == ModelOutputDelta.Kind.REASONING
                        ? ExecutionPhase.THINKING
                        : ExecutionPhase.ANSWERING;
        phaseBoundaryReached = isStreamingPhase(phase) && phase != nextPhase;
        if (delta.kind() == ModelOutputDelta.Kind.REASONING) {
            reasoning.append(delta.content());
        } else {
            answer.append(delta.content());
        }
        phase = nextPhase;
        return outputDeltaSequence;
    }

    /** 大小和时间阈值采用先到者；空增量不触发无意义写入。 */
    public boolean shouldCheckpoint(Instant now) {
        return uncheckpointedCharacters > 0
                && (uncheckpointedCharacters >= MAX_UNCHECKPOINTED_CHARACTERS
                        || phaseBoundaryReached
                        || !now.isBefore(checkpointedAt.plus(MAX_CHECKPOINT_INTERVAL)));
    }

    public void checkpointed(Instant now) {
        checkpointedAt = now;
        uncheckpointedCharacters = 0;
        phaseBoundaryReached = false;
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

    public String reasoning() {
        return reasoning.toString();
    }

    public String answer() {
        return answer.toString();
    }

    public ExecutionPhase phase() {
        return phase;
    }

    private static boolean isStreamingPhase(ExecutionPhase value) {
        return value == ExecutionPhase.THINKING || value == ExecutionPhase.ANSWERING;
    }
}
