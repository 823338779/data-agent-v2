package com.datagent.agentv2.artifact.domain;

import java.time.Instant;
import java.util.Objects;

/** 一次受控文档构建的持久任务快照；规格是构建产物的权威输入。 */
public record ArtifactBuildJob(
        String buildJobId,
        String conversationSessionId,
        String executionId,
        String conversationMessageId,
        ArtifactType artifactType,
        ArtifactOperation operation,
        ArtifactBuildStatus status,
        Object specification,
        String templateId,
        String templateVersion,
        String skillDigest,
        String scriptDigest,
        String runtimeVersion,
        String platform,
        String failureCode,
        Instant startedAt,
        Instant completedAt) {

    public ArtifactBuildJob {
        Objects.requireNonNull(buildJobId, "buildJobId");
        Objects.requireNonNull(conversationSessionId, "conversationSessionId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(conversationMessageId, "conversationMessageId");
        Objects.requireNonNull(artifactType, "artifactType");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(specification, "specification");
    }
}
