package com.datagent.agentv2.artifact.application;

import com.datagent.agentv2.artifact.domain.ArtifactType;
import com.datagent.agentv2.artifact.storage.StagedArtifact;
import tools.jackson.databind.JsonNode;

/** 已完成文件 QA 后进入正式发布事务的服务端请求。 */
public record ArtifactPublicationRequest(
        String buildJobId,
        String executionId,
        ArtifactType artifactType,
        String displayName,
        JsonNode specification,
        int specificationVersion,
        Integer pageOrSheetCount,
        StagedArtifact stagedArtifact) {}
