package com.datagent.agentv2.artifact.application;

import com.datagent.agentv2.artifact.domain.ArtifactType;

/** 对外消息投影中的可下载 Artifact 元数据，不包含存储路径。 */
public record ArtifactProjection(
        String artifactId,
        ArtifactType artifactType,
        String displayName,
        String mediaType,
        long sizeBytes,
        String sha256,
        Integer pageOrSheetCount,
        String downloadUrl) {}
