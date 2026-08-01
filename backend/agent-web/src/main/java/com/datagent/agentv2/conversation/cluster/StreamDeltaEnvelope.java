package com.datagent.agentv2.conversation.cluster;

import com.datagent.agentv2.conversation.model.ModelDelta;
import com.datagent.agentv2.conversation.stream.RunStreamCheckpointService;
import java.time.Instant;
import java.util.Objects;

/** Redis 只转发当前流增量，不承载持久化生命周期或敏感工具正文。 */
public record StreamDeltaEnvelope(
        String type,
        String tenantId,
        String sessionId,
        String runId,
        long executionAttempt,
        long modelTurnSequence,
        long streamSequence,
        String deltaType,
        String delta,
        Instant occurredAt) {

    public static final String TYPE_STREAM_DELTA = "STREAM_DELTA";
    public static final String DELTA_REASONING = "reasoning_delta";
    public static final String DELTA_ASSISTANT = "assistant_delta";

    public StreamDeltaEnvelope {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(deltaType, "deltaType");
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (!TYPE_STREAM_DELTA.equals(type)
                || executionAttempt < 1
                || modelTurnSequence < 1
                || streamSequence < 1) {
            throw new IllegalArgumentException("invalid stream delta envelope");
        }
    }

    public static StreamDeltaEnvelope from(RunStreamCheckpointService.PublishedDelta published) {
        String deltaType =
                published.kind() == ModelDelta.Kind.REASONING ? DELTA_REASONING : DELTA_ASSISTANT;
        return new StreamDeltaEnvelope(
                TYPE_STREAM_DELTA,
                published.tenantId(),
                published.sessionId(),
                published.runId(),
                published.executionAttempt(),
                published.modelTurnSequence(),
                published.streamSequence(),
                deltaType,
                published.delta(),
                published.occurredAt());
    }
}
