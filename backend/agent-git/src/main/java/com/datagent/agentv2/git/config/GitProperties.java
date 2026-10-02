package com.datagent.agentv2.git.config;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Git 本地能力的固定服务端配置；这些值不得由模型输入覆盖。
 *
 * @param executable 部署指定的 Git 可执行文件，不接受模型参数替换
 * @param remoteUri 所有会话共用的固定仓库地址，可能包含部署凭据，不得原样记录
 * @param workspaceRoot 私有工作区和不可变快照根目录，构造时归一化为绝对路径
 * @param commandTimeout 单条 Git 子进程时间上限，必须为正
 * @param maxCommandOutputBytes 单条 Git 命令允许捕获的 stdout/stderr 字节数
 * @param maxFileBytes 单个工作区文本文件的 UTF-8 字节上限
 * @param maxFilesPerOperation 单次结构化文件操作的文件数上限
 * @param maxCandidateFiles 单份冻结候选的变更文件数上限
 * @param maxCandidateBytes 候选变更涉及的新旧文件内容字节总上限
 * @param maxDiffBytes 候选完整 diff 的 UTF-8 字节上限
 */
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
        // 子进程在各会话 clone 中执行；固定绝对根目录，避免临时 index 相对于 clone 二次解析。
        workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }
}
