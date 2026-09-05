package com.datagent.agentv2.artifact.storage;

import com.datagent.agentv2.artifact.domain.ArtifactType;

/** 构建中的文件引用，仅包含受控根目录内的相对存储键。 */
public record StagedArtifact(String buildJobId, ArtifactType artifactType, String storageKey) {}
