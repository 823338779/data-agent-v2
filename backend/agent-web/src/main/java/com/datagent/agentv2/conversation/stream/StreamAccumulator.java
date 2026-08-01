package com.datagent.agentv2.conversation.stream;

import com.datagent.agentv2.conversation.model.ModelDelta;
import java.time.Duration;
import java.time.Instant;

/** 累计单个 attempt/turn 的流正文，并判断有界 checkpoint 时机。 */
public final class StreamAccumulator {

    public static final Duration MAX_CHECKPOINT_INTERVAL = Duration.ofMillis(250);
    public static final int MAX_UNCHECKPOINTED_CHARACTERS = 512;
    private static final String PHASE_THINKING = "THINKING";
    private static final String PHASE_ANSWERING = "ANSWERING";

    private final long executionAttempt;
    private final long modelTurnSequence;
    private final StringBuilder reasoning;
    private final StringBuilder answer;
    private long streamSequence;
    private int uncheckpointedCharacters;
    private boolean phaseBoundaryReached;
    private Instant checkpointedAt;
    private String phase;

    public StreamAccumulator(
            long executionAttempt,
            long modelTurnSequence,
            long streamSequence,
            String reasoning,
            String answer,
            String phase,
            Instant checkpointedAt) {
        this.executionAttempt = executionAttempt;
        this.modelTurnSequence = modelTurnSequence;
        this.streamSequence = streamSequence;
        this.reasoning = new StringBuilder(reasoning);
        this.answer = new StringBuilder(answer);
        this.phase = phase;
        this.checkpointedAt = checkpointedAt;
    }

    /** 先分配并记录发布序号，再把正文累计到当前投影。 */
    public long append(ModelDelta delta) {
        streamSequence++;
        uncheckpointedCharacters += delta.content().length();
        String nextPhase =
                delta.kind() == ModelDelta.Kind.REASONING ? PHASE_THINKING : PHASE_ANSWERING;
        phaseBoundaryReached = isStreamingPhase(phase) && !phase.equals(nextPhase);
        if (delta.kind() == ModelDelta.Kind.REASONING) {
            reasoning.append(delta.content());
        } else {
            answer.append(delta.content());
        }
        phase = nextPhase;
        return streamSequence;
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

    public long executionAttempt() {
        return executionAttempt;
    }

    public long modelTurnSequence() {
        return modelTurnSequence;
    }

    public long streamSequence() {
        return streamSequence;
    }

    public String reasoning() {
        return reasoning.toString();
    }

    public String answer() {
        return answer.toString();
    }

    public String phase() {
        return phase;
    }

    private static boolean isStreamingPhase(String value) {
        return PHASE_THINKING.equals(value) || PHASE_ANSWERING.equals(value);
    }
}
