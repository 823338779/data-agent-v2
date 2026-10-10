package com.datagent.agentv2.git.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 服务端固定 Git 配置；仅在 enabled=true 时绑定并验证，不接受模型覆盖。
 *
 * @param repositoryId 历史仓库业务标识，不作为新开发会话默认值
 * @param authorName 候选创建时冻结的提交作者名称
 * @param authorEmail 候选创建时冻结的提交作者邮箱
 * @param remoteUri 可选历史仓库地址；新开发目标由项目配置固定
 * @param workspaceRoot 私有草稿与候选根目录，不对用户公开
 * @param commandTimeout 单条子进程截止时间
 * @param maxCommandOutputBytes 单次 stdout/stderr 字节上限
 * @param maxFileBytes 单个 UTF-8 文件字节上限
 * @param maxFilesPerOperation 单次编辑文件数上限
 * @param maxCandidateFiles 候选文件数上限
 * @param maxCandidateBytes 候选新旧内容总字节上限
 * @param maxDiffBytes 冻结 diff 总字节上限
 */
@ConfigurationProperties("data-agent.git")
public record GitSettings(
        @DefaultValue("git") String executable,
        @DefaultValue("") String remoteUri,
        @DefaultValue("./data/git") Path workspaceRoot,
        @DefaultValue("30s") Duration commandTimeout,
        @DefaultValue("4194304") int maxCommandOutputBytes,
        @DefaultValue("524288") long maxFileBytes,
        @DefaultValue("20") int maxFilesPerOperation,
        @DefaultValue("100") int maxCandidateFiles,
        @DefaultValue("5242880") long maxCandidateBytes,
        @DefaultValue("2097152") int maxDiffBytes,
        @DefaultValue("metrics") String repositoryId,
        @DefaultValue("Data Agent") String authorName,
        @DefaultValue("data-agent@localhost") String authorEmail) {}
