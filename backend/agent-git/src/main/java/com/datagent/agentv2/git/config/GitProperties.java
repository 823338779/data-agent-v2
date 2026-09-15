package com.datagent.agentv2.git.config;

import java.nio.file.Path;
import java.time.Duration;

/** Git 本地能力的固定服务端配置；这些值不得由模型输入覆盖。 */
public record GitProperties(
        String executable,
        String remoteUri,
        Path workspaceRoot,
        Duration commandTimeout,
        int maxCommandOutputBytes,
        long maxFileBytes,
        int maxFilesPerOperation,
        int maxCandidateFiles,
        long maxCandidateBytes,
        int maxDiffBytes) {
    public GitProperties {
        if (executable == null
                || executable.isBlank()
                || remoteUri == null
                || remoteUri.isBlank()
                || workspaceRoot == null
                || commandTimeout == null
                || commandTimeout.isNegative()
                || commandTimeout.isZero()
                || maxCommandOutputBytes <= 0
                || maxFileBytes <= 0
                || maxFilesPerOperation <= 0
                || maxCandidateFiles <= 0
                || maxCandidateBytes <= 0
                || maxDiffBytes <= 0) {
            throw new IllegalArgumentException("Git 配置不完整或上限无效");
        }
    }
}
