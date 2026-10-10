package com.datagent.agentv2.git.config;

import com.datagent.agentv2.git.application.*;
import com.datagent.agentv2.git.local.*;
import com.datagent.agentv2.git.model.GitException;
import com.datagent.agentv2.git.port.GitBranchRegistry;
import com.datagent.agentv2.git.port.GitCandidateValidation;
import com.datagent.agentv2.persistence.service.git.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

/** Git 模块装配；所有应用服务共享同一工作区和锁，构造不 clone 仓库。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "data-agent.git", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(GitSettings.class)
public class GitConfiguration {
    @Bean
    GitProperties gitProperties(GitSettings s) {
        return new GitProperties(
                s.executable(),
                s.remoteUri(),
                s.workspaceRoot(),
                s.commandTimeout(),
                s.maxCommandOutputBytes(),
                s.maxFileBytes(),
                s.maxFilesPerOperation(),
                s.maxCandidateFiles(),
                s.maxCandidateBytes(),
                s.maxDiffBytes());
    }

    @Bean
    GitApplicationProperties gitApplicationProperties(GitSettings s) {
        return new GitApplicationProperties(s.repositoryId(), s.authorName(), s.authorEmail());
    }

    @Bean
    GitWorkspaceLocks gitWorkspaceLocks() {
        return new GitWorkspaceLocks();
    }

    @Bean
    GitRepositoryClient gitRepositoryClient(GitProperties p) {
        return new GitRepositoryClient(p);
    }

    @Bean
    GitWorkspaceFiles gitWorkspaceFiles(GitProperties p) {
        return new GitWorkspaceFiles(p);
    }

    @Bean
    GitSnapshotStore gitSnapshotStore(GitProperties p) {
        return new GitSnapshotStore(p);
    }

    @Bean
    GitWorkspaceService gitWorkspaceService(
            GitWorkspacePersistenceService w,
            GitChangePersistenceService c,
            GitRepositoryClient git,
            GitWorkspaceFiles files,
            GitProperties p,
            GitApplicationProperties a,
            GitWorkspaceLocks locks,
            ObjectProvider<GitBranchRegistry> branches,
            GitSessionWorkspaceService sessions) {
        return new GitWorkspaceService(
                w, c, git, files, p, a, locks, branches.getIfAvailable(), sessions);
    }

    @Bean
    GitChangeService gitChangeService(
            GitWorkspaceService w,
            GitChangePersistenceService c,
            GitSnapshotStore snapshots,
            GitRepositoryClient git,
            GitApplicationProperties a,
            ObjectProvider<GitCandidateValidation> validations) {
        return new GitChangeService(
                w,
                c,
                snapshots,
                git,
                a,
                validations.getIfAvailable(() -> (session, snapshot) -> null),
                (execution, candidate) -> {
                    throw new GitException("GIT_LEGACY_WRITE_RETIRED", "旧候选写入已退役");
                });
    }
}
