package com.datagent.agentv2.context.hierarchical.domain;

import java.util.Objects;

/** 带 execution fencing 和乐观锁版本的可恢复压缩作业。 */
public record HierarchicalCompactionJob(
        String jobId,
        String conversationSessionId,
        String executionId,
        long executionAttempt,
        String ownerInstanceId,
        long inputUpToMessageSequence,
        long baseCheckpointVersion,
        long baseStrategyGeneration,
        String planDocument,
        Status status,
        String failureCode,
        long version) {

    /** 插件私有的作业生命周期，不泄漏到共享 agent-domain。 */
    public enum Status {
        PLANNED,
        GENERATING,
        READY_TO_PUBLISH,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    /** 确保恢复所需的固定边界和 fencing 身份完整。 */
    public HierarchicalCompactionJob {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(conversationSessionId, "conversationSessionId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(ownerInstanceId, "ownerInstanceId");
        Objects.requireNonNull(planDocument, "planDocument");
        Objects.requireNonNull(status, "status");
        if (executionAttempt <= 0
                || inputUpToMessageSequence < 0
                || baseCheckpointVersion < 0
                || baseStrategyGeneration < 0
                || version < 0) {
            throw new IllegalArgumentException("Invalid hierarchical compaction job");
        }
    }
}
