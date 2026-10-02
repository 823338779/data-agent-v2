package com.datagent.agentv2.git.application;

import com.datagent.agentv2.git.config.*;
import com.datagent.agentv2.git.local.*;
import com.datagent.agentv2.git.model.*;
import com.datagent.agentv2.persistence.entity.git.*;
import com.datagent.agentv2.persistence.service.git.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;

/** 会话草稿门面；互斥覆盖读写、完整性检查及冻结，数据库更新不跨文件/Git I/O。 */
@RequiredArgsConstructor
public final class GitWorkspaceService {
    private final GitWorkspacePersistenceService records;
    private final GitChangePersistenceService changes;
    private final GitRepositoryClient git;
    private final GitWorkspaceFiles files;
    private final GitProperties properties;
    private final GitApplicationProperties application;
    private final GitWorkspaceLocks locks;

    public GitWorkspaceView prepare(String sessionId, boolean restartDraft) {
        return locked(
                sessionId,
                () -> {
                    var w = records.find(sessionId, application.repositoryId());
                    if (w == null) {
                        w = new GitWorkspaceEntity();
                        w.setWorkspaceId("gw_" + UUID.randomUUID());
                        w.setConversationSessionId(sessionId);
                        w.setRepositoryId(application.repositoryId());
                        w.setDraftRevision(0L);
                        w.setVersion(0L);
                        w.setStorageKey("workspaces/" + UUID.randomUUID());
                        w.setStatus("MUTATING");
                        // 先登记创建意图。进程在 clone 中退出时，下次不会静默重建这个已登记工作区。
                        records.save(w);
                        initialize(w);
                    } else if (restartDraft) {
                        assertEditable(w);
                        changes.invalidatePending(w.getWorkspaceId());
                        w.setActiveChangeId(null);
                        w.setStatus("MUTATING");
                        persist(w);
                        // 旧 clone 与候选文件保留作为归档；新草稿使用全新服务端键，绝不 reset 掉审阅证据。
                        w = records.find(sessionId, application.repositoryId());
                        w.setStorageKey("workspaces/" + UUID.randomUUID());
                        w.setDraftRevision(w.getDraftRevision() + 1);
                        persist(w);
                        initialize(records.find(sessionId, application.repositoryId()));
                    } else {
                        ensureIntegrity(w);
                    }
                    return GitWorkspaceView.composeFromWorkspace(
                            records.find(sessionId, application.repositoryId()));
                });
    }

    private void initialize(GitWorkspaceEntity w) {
        try {
            git.cloneRepository(path(w));
            w.setBaseCommit(git.head(path(w)));
            w.setDraftDigest(fingerprint(path(w)));
            w.setStatus("READY");
            persist(w);
        } catch (RuntimeException failure) {
            markBroken(w);
            throw failure;
        }
    }

    public FileReadResult read(String sessionId, String path) {
        return locked(sessionId, () -> files.read(path(require(sessionId)), path));
    }

    public List<String> list(String sessionId, String directory) {
        return locked(sessionId, () -> files.list(path(require(sessionId)), directory));
    }

    public List<FileSearchMatch> search(String sessionId, String directory, String query) {
        return locked(sessionId, () -> files.search(path(require(sessionId)), directory, query));
    }

    public GitSnapshot diff(String sessionId) {
        return locked(sessionId, () -> git.freeze(path(require(sessionId))));
    }

    /** 工作区锁内冻结并复制完整旧/新正文，网络校验必须在返回后执行。 */
    public GitDraftSnapshot freezeDraft(String sessionId, long expectedRevision) {
        return locked(
                sessionId,
                () -> {
                    var w = require(sessionId);
                    assertEditable(w);
                    if (w.getDraftRevision() != expectedRevision)
                        throw new GitException("GIT_CONFLICT", "草稿修订号已变化");
                    var snapshot = git.freeze(path(w));
                    git.retainSnapshot(
                            path(w), "validation_" + UUID.randomUUID(), snapshot.treeId());
                    return draftSnapshot(w, snapshot);
                });
    }

    /** 调用方已持有工作区锁；候选验证复用刚冻结的 tree，不重复从当前文件推断正文。 */
    GitDraftSnapshot draftSnapshot(GitWorkspaceEntity w, GitSnapshot snapshot) {
        var entries =
                snapshot.changes().stream()
                        .map(
                                change ->
                                        new GitDraftSnapshot.File(
                                                change.status(),
                                                change.path(),
                                                change.status().equals("A")
                                                        ? null
                                                        : git.readTreeFile(
                                                                path(w),
                                                                w.getBaseCommit(),
                                                                change.path()),
                                                change.status().equals("D")
                                                        ? null
                                                        : git.readTreeFile(
                                                                path(w),
                                                                snapshot.treeId(),
                                                                change.path())))
                        .toList();
        return new GitDraftSnapshot(
                w.getConversationSessionId(),
                w.getWorkspaceId(),
                w.getBaseCommit(),
                snapshot.treeId(),
                w.getDraftRevision(),
                entries);
    }

    public GitWorkspaceView apply(String sessionId, List<FileOperation> operations) {
        return locked(
                sessionId,
                () -> {
                    var w = require(sessionId);
                    assertEditable(w);
                    String before = w.getDraftDigest();
                    // 写入前持久化屏障，崩溃后禁止把未登记的新内容当作旧草稿批准。
                    w.setStatus("MUTATING");
                    persist(w);
                    try {
                        files.apply(path(w), operations);
                    } finally {
                        var current = records.find(sessionId, application.repositoryId());
                        String after = fingerprint(path(current));
                        if (!before.equals(after)) {
                            changes.invalidatePending(current.getWorkspaceId());
                            current.setDraftRevision(current.getDraftRevision() + 1);
                        }
                        current.setDraftDigest(after);
                        current.setStatus("READY");
                        persist(current);
                    }
                    return GitWorkspaceView.composeFromWorkspace(
                            records.find(sessionId, application.repositoryId()));
                });
    }

    <T> T locked(String session, Supplier<T> action) {
        return locks.workspace(application.repositoryId(), session, action);
    }

    <T> T submitting(String session, Supplier<T> action) {
        return locks.repository(application.repositoryId(), () -> locked(session, action));
    }

    GitWorkspaceEntity require(String session) {
        var w = records.find(session, application.repositoryId());
        if (w == null) {
            throw new GitException("GIT_WORKSPACE_NOT_FOUND", "请先准备当前会话工作区");
        }
        ensureIntegrity(w);
        return w;
    }

    GitWorkspaceEntity record(String session) {
        var w = records.find(session, application.repositoryId());
        if (w == null) {
            throw new GitException("GIT_WORKSPACE_NOT_FOUND", "工作区不存在");
        }
        return w;
    }

    Path path(GitWorkspaceEntity w) {
        Path root = properties.workspaceRoot().toAbsolutePath().normalize();
        Path result = root.resolve(w.getStorageKey()).normalize();
        if (!result.startsWith(root) || result.equals(root)) {
            throw new GitException("GIT_WORKSPACE_INVALID", "工作区存储键无效");
        }
        return result;
    }

    void assertEditable(GitWorkspaceEntity w) {
        if (w.getActiveChangeId() != null) {
            var c = changes.find(w.getActiveChangeId());
            if (c != null && List.of("SUBMITTING", "VERIFYING").contains(c.getStatus())) {
                throw new GitException("GIT_CONFLICT", "提交或结果核实期间禁止修改工作区");
            }
        }
    }

    void ensureIntegrity(GitWorkspaceEntity w) {
        try {
            if (!"READY".equals(w.getStatus())
                    || !Objects.equals(w.getDraftDigest(), fingerprint(path(w)))
                    || !Objects.equals(w.getBaseCommit(), git.head(path(w)))) {
                throw new GitException("GIT_WORKSPACE_CORRUPT", "工作区完整性校验失败，请显式重新开始草稿");
            }
        } catch (RuntimeException failure) {
            markBroken(w);
            throw new GitException("GIT_WORKSPACE_CORRUPT", "工作区完整性校验失败，请显式重新开始草稿", failure);
        }
    }

    void markBroken(GitWorkspaceEntity w) {
        changes.invalidatePending(w.getWorkspaceId());
        var fresh = records.find(w.getConversationSessionId(), application.repositoryId());
        if (fresh != null) {
            fresh.setStatus("BROKEN");
            persist(fresh);
        }
    }

    void accepted(GitWorkspaceEntity w, String commit) {
        if (!Objects.equals(w.getDraftDigest(), fingerprint(path(w)))) {
            throw new GitException("GIT_WORKSPACE_CORRUPT", "远端已接受，但本地草稿完整性校验失败");
        }
        // 更新引用只在远端已接受之后执行；先写 MUTATING，崩溃留下的中间态由核实流程继续收敛。
        w.setStatus("MUTATING");
        persist(w);
        git.acceptSubmittedCommit(path(w), commit);
        var fresh = record(w.getConversationSessionId());
        fresh.setBaseCommit(commit);
        fresh.setDraftDigest(fingerprint(path(fresh)));
        fresh.setStatus("READY");
        persist(fresh);
    }

    private void persist(GitWorkspaceEntity w) {
        if (!records.replace(w)) {
            throw new GitException("GIT_CONFLICT", "工作区版本已变化");
        }
    }

    static String fingerprint(Path root) {
        try {
            if (!Files.isDirectory(root.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException();
            }
            var digest = MessageDigest.getInstance("SHA-256");
            try (var paths = Files.walk(root)) {
                for (Path p : paths.sorted().toList()) {
                    Path relative = root.relativize(p);
                    if (relative.toString().isEmpty()
                            || relative.getName(0).toString().equals(".git")) {
                        continue;
                    }
                    if (Files.isSymbolicLink(p)) {
                        throw new IllegalStateException();
                    }
                    if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IllegalStateException();
                    }
                    digest.update(
                            relative.toString()
                                    .replace('\\', '/')
                                    .getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    try (InputStream input = Files.newInputStream(p)) {
                        byte[] b = new byte[8192];
                        int count;
                        while ((count = input.read(b)) >= 0) {
                            digest.update(b, 0, count);
                        }
                    }
                    digest.update((byte) 0);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new GitException("GIT_WORKSPACE_CORRUPT", "工作区文件缺失或损坏", failure);
        }
    }
}
