package com.datagent.agentv2.artifact.storage;

import com.datagent.agentv2.artifact.domain.ArtifactType;

/** 原子发布后的不可变文件事实。 */
public record StoredArtifact(
        String artifactId,
        ArtifactType artifactType,
        String storageKey,
        long sizeBytes,
        String sha256) {}
