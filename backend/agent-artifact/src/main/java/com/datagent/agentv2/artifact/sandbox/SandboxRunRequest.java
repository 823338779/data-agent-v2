package com.datagent.agentv2.artifact.sandbox;

import com.datagent.agentv2.artifact.domain.ArtifactType;
import java.time.Duration;

/** 交给平台 Runner 的受控参数，不允许携带任意命令行或绝对路径。 */
public record SandboxRunRequest(
        String buildJobId,
        ArtifactType artifactType,
        String fixedEntrypoint,
        String inputStorageKey,
        String outputStorageKey,
        Duration timeout,
        long maxOutputBytes) {}
