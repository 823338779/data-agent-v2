package com.datagent.agentv2.artifact.domain;

import java.util.Objects;

/** 通过 QA 后发布的不可变 Office 文件元数据；二进制内容保存在受控本机目录。 */
public record ArtifactObject(
        String artifactId,
        String conversationSessionId,
        String buildJobId,
        String sourceArtifactId,
        int specificationVersion,
        ArtifactType artifactType,
        ArtifactStatus status,
        String displayName,
        String mediaType,
        String storageKey,
        long sizeBytes,
        String sha256,
        Integer pageOrSheetCount) {

    public ArtifactObject {
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(conversationSessionId, "conversationSessionId");
        Objects.requireNonNull(buildJobId, "buildJobId");
        Objects.requireNonNull(artifactType, "artifactType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(storageKey, "storageKey");
        Objects.requireNonNull(sha256, "sha256");
        if (specificationVersion < 1 || sizeBytes < 1L) {
            throw new IllegalArgumentException("artifact version and size must be positive");
        }
        if (pageOrSheetCount != null && pageOrSheetCount < 1) {
            throw new IllegalArgumentException("pageOrSheetCount must be positive");
        }
    }
}
