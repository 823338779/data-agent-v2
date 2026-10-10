package com.datagent.agentv2.persistence.service.development;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.datagent.agentv2.persistence.entity.development.DevelopmentProjectEntity;
import com.datagent.agentv2.persistence.entity.development.DevelopmentSessionEntity;
import com.datagent.agentv2.persistence.mapper.development.DevelopmentProjectMapper;
import com.datagent.agentv2.persistence.mapper.development.DevelopmentSessionMapper;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 开发配置、冻结目标与本地元数据；事务不跨文件或网络 I/O。 */
@Service
@RequiredArgsConstructor
public class DevelopmentContextPersistenceService
        extends ServiceImpl<DevelopmentProjectMapper, DevelopmentProjectEntity> {
    private static final String FOR_UPDATE = "FOR UPDATE";
    private final DevelopmentSessionMapper sessions;

    private static final String MULTIPLE_REPOSITORIES = "MULTIPLE_REPOSITORIES";
    private static final String UNRESOLVED_REPOSITORY = "UNRESOLVED_REPOSITORY";
    private static final String MULTIPLE_BRANCHES = "MULTIPLE_BRANCHES";
    private static final String MIGRATED = "MIGRATED";
    private static final String AMBIGUOUS_SOURCE = "AMBIGUOUS_SOURCE";
    private static final String READY = "READY";
    private static final String HEADS = "refs/heads/";

    /** 逐工作区报告；旧记录不删除，任何来源歧义禁用该项目的新开发操作。 */
    public record MigrationReport(String workspaceId, String sessionId, String status) {}

    @Transactional
    public List<MigrationReport> migrateLegacy(
            String projectId, java.util.Map<String, String> repositories) {
        var project =
                lambdaQuery()
                        .eq(DevelopmentProjectEntity::getProjectId, projectId)
                        .last(FOR_UPDATE)
                        .one();
        if (project == null) throw new IllegalStateException("迁移前必须显式配置 Agent 项目和仓库映射");
        var sources = baseMapper.legacySources(projectId);
        var resolved =
                sources.stream().map(s -> repositories.get(s.repositoryId())).distinct().toList();
        String diagnostic =
                resolved.size() > 1
                        ? MULTIPLE_REPOSITORIES
                        : resolved.contains(null)
                                        || !resolved.isEmpty()
                                                && !resolved.getFirst()
                                                        .equals(project.getRepository())
                                ? UNRESOLVED_REPOSITORY
                                : null;
        if (sources.stream()
                .collect(java.util.stream.Collectors.groupingBy(s -> s.sessionId()))
                .values()
                .stream()
                .anyMatch(group -> group.stream().map(s -> s.gitRef()).distinct().count() > 1))
            diagnostic = MULTIPLE_BRANCHES;
        if (sources.stream()
                        .anyMatch(
                                s ->
                                        s.gitRef() == null
                                                || !s.gitRef().startsWith(HEADS)
                                                || s.commitSha() == null
                                                || !s.commitSha()
                                                        .matches("[0-9a-f]{40}(?:[0-9a-f]{24})?"))
                || sources.stream()
                        .collect(java.util.stream.Collectors.groupingBy(s -> s.sessionId()))
                        .values()
                        .stream()
                        .anyMatch(
                                group ->
                                        group.stream().map(s -> s.commitSha()).distinct().count()
                                                > 1)) diagnostic = AMBIGUOUS_SOURCE;
        if (diagnostic != null) {
            String reason = diagnostic;
            lambdaUpdate()
                    .eq(DevelopmentProjectEntity::getId, project.getId())
                    .set(DevelopmentProjectEntity::getEnabled, false)
                    .set(DevelopmentProjectEntity::getDiagnostic, reason)
                    .update();
            return sources.stream()
                    .map(s -> new MigrationReport(s.workspaceId(), s.sessionId(), reason))
                    .toList();
        }
        // 一致来源才映射已推送 SHA；不迁移 validation 成功、不复制本地未推送正文。
        for (var source : sources) {
            var existing = session(source.sessionId());
            if (existing != null) continue;
            var row = new DevelopmentSessionEntity();
            row.setSessionId(source.sessionId());
            row.setProjectId(projectId);
            row.setRepository(project.getRepository());
            row.setBranchName(source.gitRef().substring(HEADS.length()));
            row.setBaselineSha(source.commitSha());
            row.setPushedSha(source.commitSha());
            row.setConfigSnapshot(project.getConfigJson());
            row.setStorageKey(java.util.UUID.randomUUID().toString());
            row.setRevision(0L);
            row.setVersion(0L);
            row.setLocalState(READY);
            sessions.insert(row);
        }
        return sources.stream()
                .map(s -> new MigrationReport(s.workspaceId(), s.sessionId(), MIGRATED))
                .toList();
    }

    public DevelopmentProjectEntity project(String projectId) {
        return lambdaQuery().eq(DevelopmentProjectEntity::getProjectId, projectId).one();
    }

    public DevelopmentSessionEntity session(String sessionId) {
        return sessions.selectOne(
                Wrappers.<DevelopmentSessionEntity>lambdaQuery()
                        .eq(DevelopmentSessionEntity::getSessionId, sessionId));
    }

    @Transactional
    public DevelopmentProjectEntity configure(DevelopmentProjectEntity next, long expectedVersion) {
        var prior =
                lambdaQuery()
                        .eq(DevelopmentProjectEntity::getProjectId, next.getProjectId())
                        .last(FOR_UPDATE)
                        .one();
        if (prior == null) {
            if (expectedVersion != 0) throw new IllegalStateException("配置版本冲突");
            next.setVersion(1L);
            save(next);
        } else {
            if (!Boolean.TRUE.equals(prior.getEnabled())
                    || prior.getVersion() != expectedVersion
                    || !Objects.equals(prior.getRepository(), next.getRepository())
                            && hasSessions(next.getProjectId())) {
                throw new IllegalStateException("已绑定仓库不可更换或配置版本冲突");
            }
            lambdaUpdate()
                    .eq(DevelopmentProjectEntity::getId, prior.getId())
                    .set(DevelopmentProjectEntity::getRepository, next.getRepository())
                    .set(DevelopmentProjectEntity::getConfigJson, next.getConfigJson())
                    .set(DevelopmentProjectEntity::getVersion, expectedVersion + 1)
                    .update();
        }
        return project(next.getProjectId());
    }

    public boolean hasSessions(String projectId) {
        return sessions.selectCount(
                        Wrappers.<DevelopmentSessionEntity>lambdaQuery()
                                .eq(DevelopmentSessionEntity::getProjectId, projectId))
                > 0;
    }

    @Transactional
    public DevelopmentSessionEntity bind(DevelopmentSessionEntity next) {
        // 与配置更新、停用共用项目锁，保证绑定不会落在已停用项目上。
        var project =
                lambdaQuery()
                        .eq(DevelopmentProjectEntity::getProjectId, next.getProjectId())
                        .last(FOR_UPDATE)
                        .one();
        if (project == null || !Boolean.TRUE.equals(project.getEnabled())) {
            throw new IllegalStateException("项目不能开始新的开发操作");
        }
        if (!Objects.equals(project.getRepository(), next.getRepository())) {
            throw new IllegalStateException("仓库配置已改变");
        }
        var prior = session(next.getSessionId());
        if (prior != null) {
            if (!Objects.equals(prior.getProjectId(), next.getProjectId())
                    || !Objects.equals(prior.getBranchName(), next.getBranchName())) {
                throw new IllegalStateException("开发会话不能更换项目或分支");
            }
            return prior;
        }
        sessions.insert(next);
        return session(next.getSessionId());
    }

    @Transactional
    public void disable(String projectId) {
        lambdaUpdate()
                .eq(DevelopmentProjectEntity::getProjectId, projectId)
                .set(DevelopmentProjectEntity::getEnabled, false)
                .setIncrBy(DevelopmentProjectEntity::getVersion, 1)
                .update();
    }

    /** 文件更新前后使用同一 revision/version 屏障；不能覆盖其他操作的新状态。 */
    public boolean replaceSession(DevelopmentSessionEntity next) {
        return sessions.update(
                        new DevelopmentSessionEntity(),
                        Wrappers.<DevelopmentSessionEntity>lambdaUpdate()
                                .eq(DevelopmentSessionEntity::getSessionId, next.getSessionId())
                                .eq(DevelopmentSessionEntity::getVersion, next.getVersion())
                                .set(DevelopmentSessionEntity::getStorageKey, next.getStorageKey())
                                .set(
                                        DevelopmentSessionEntity::getBaselineSha,
                                        next.getBaselineSha())
                                .set(
                                        DevelopmentSessionEntity::getLocalDigest,
                                        next.getLocalDigest())
                                .set(DevelopmentSessionEntity::getLocalState, next.getLocalState())
                                .set(DevelopmentSessionEntity::getRevision, next.getRevision())
                                .set(DevelopmentSessionEntity::getPushedSha, next.getPushedSha())
                                .set(
                                        DevelopmentSessionEntity::getSelectedBuildId,
                                        next.getSelectedBuildId())
                                .set(DevelopmentSessionEntity::getVersion, next.getVersion() + 1))
                == 1;
    }

    public List<DevelopmentSessionEntity> boundSessions(String projectId) {
        return sessions.selectList(
                Wrappers.<DevelopmentSessionEntity>lambdaQuery()
                        .eq(DevelopmentSessionEntity::getProjectId, projectId));
    }

    /** 首次选择只在空值时写入；并发解析部署的请求共用获胜者，不让活动版本暗换。 */
    @Transactional
    public java.util.UUID selectBuildIfAbsent(String sessionId, java.util.UUID buildId) {
        sessions.update(
                new DevelopmentSessionEntity(),
                Wrappers.<DevelopmentSessionEntity>lambdaUpdate()
                        .eq(DevelopmentSessionEntity::getSessionId, sessionId)
                        .isNull(DevelopmentSessionEntity::getSelectedBuildId)
                        .set(DevelopmentSessionEntity::getSelectedBuildId, buildId.toString())
                        .setIncrBy(DevelopmentSessionEntity::getVersion, 1));
        var current = session(sessionId);
        if (current == null) throw new IllegalStateException("开发会话不存在");
        return java.util.UUID.fromString(current.getSelectedBuildId());
    }
}
