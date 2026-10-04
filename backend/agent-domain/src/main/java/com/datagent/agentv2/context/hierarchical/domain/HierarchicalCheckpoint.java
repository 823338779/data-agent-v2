package com.datagent.agentv2.context.hierarchical.domain;

import java.util.Objects;

/**
 * 会话当前原子发布的滚动 Cold 引用与单调边界。
 *
 * @param checkpointId 持久化业务标识，创建后不变
 * @param conversationSessionId 所属会话，CAS 不允许跨会话替换
 * @param activeColdId 当前摘要标识；尚未压缩时为空
 * @param evaluatedUpToConversationMessageSequence 已评估的最高源消息序号，发布时不得倒退
 * @param strategyGeneration 成功发布后递增的业务代次
 * @param version 数据库 CAS 版本，与业务代次同时比较
 */
public record HierarchicalCheckpoint(
        String checkpointId,
        String conversationSessionId,
        String activeColdId,
        long evaluatedUpToConversationMessageSequence,
        long strategyGeneration,
        long version,
        /** V3 已覆盖位置；V2 为空。 */
        ContextStepCursor coveredThrough,
        /** V3 已观察位置，可以超前于覆盖位置。 */
        ContextStepCursor evaluatedThrough,
        /** 冻结策略版本。 */
        int policyVersion,
        /** 摘要协议版本。 */
        int schemaVersion) {
    public HierarchicalCheckpoint(
            String checkpointId,
            String conversationSessionId,
            String activeColdId,
            long evaluatedUpToConversationMessageSequence,
            long strategyGeneration,
            long version) {
        this(
                checkpointId,
                conversationSessionId,
                activeColdId,
                evaluatedUpToConversationMessageSequence,
                strategyGeneration,
                version,
                null,
                null,
                2,
                2);
    }

    public HierarchicalCheckpoint {
        if (policyVersion != schemaVersion
                || (schemaVersion != 2 && schemaVersion != 3)
                || (schemaVersion == 3
                        && (coveredThrough == null
                                || evaluatedThrough == null
                                || coveredThrough.compareTo(evaluatedThrough) > 0))
                || (schemaVersion == 2 && (coveredThrough != null || evaluatedThrough != null))) {
            throw new IllegalArgumentException(
                    "Invalid hierarchical checkpoint versions or cursors");
        }

        Objects.requireNonNull(checkpointId, "checkpointId");
        Objects.requireNonNull(conversationSessionId, "conversationSessionId");
        if (activeColdId != null && activeColdId.isBlank()) {
            throw new IllegalArgumentException("activeColdId must be null or non-blank");
        }
        if (evaluatedUpToConversationMessageSequence < 0 || strategyGeneration < 0 || version < 0) {
            throw new IllegalArgumentException("Invalid hierarchical checkpoint");
        }
    }
}
