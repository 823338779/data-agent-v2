package com.datagent.agentv2.context.hierarchical.domain;

import com.datagent.agentv2.conversation.context.ContextMessage;
import java.util.List;
import java.util.Objects;

/** 不可拆分的模型调用及其全部工具观察；未完成组不得进入压缩前缀。 */
public record ContextStepGroup(
        /** 本组覆盖的左开边界。 */
        ContextStepCursor fromExclusive,
        /** 本组覆盖的右闭边界。 */
        ContextStepCursor throughInclusive,
        /** 保留调用 ID 和观察配对的协议消息。 */
        List<ContextMessage> messages,
        /** 仅本组真实来源的 SHA-256，消息后续追加不影响它。 */
        String sourceDigest,
        /** 本组是否具备完整终态，决定是否可压缩。 */
        boolean complete,
        /** 按工具调用 ID 和方向索引的归档引用，仅用于历史投影。 */
        java.util.Map<String, ContextPayloadReference> payloadReferences) {
    public ContextStepGroup(
            ContextStepCursor fromExclusive,
            ContextStepCursor throughInclusive,
            List<ContextMessage> messages,
            String sourceDigest,
            boolean complete) {
        this(fromExclusive, throughInclusive, messages, sourceDigest, complete, java.util.Map.of());
    }

    public static String payloadKey(String callId, ContextPayloadReference.Direction direction) {
        return callId + direction.name();
    }

    public ContextStepGroup {
        Objects.requireNonNull(fromExclusive, "fromExclusive");
        Objects.requireNonNull(throughInclusive, "throughInclusive");
        messages = List.copyOf(messages);
        payloadReferences = java.util.Map.copyOf(payloadReferences);
        Objects.requireNonNull(sourceDigest, "sourceDigest");
        if (fromExclusive.compareTo(throughInclusive) >= 0 || sourceDigest.isBlank()) {
            throw new IllegalArgumentException("Invalid context step group");
        }
    }
}
