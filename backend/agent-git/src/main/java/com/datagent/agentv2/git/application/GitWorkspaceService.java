package com.datagent.agentv2.git.application;

import com.datagent.agentv2.git.config.*;
import com.datagent.agentv2.git.local.*;
import com.datagent.agentv2.git.model.*;
import com.datagent.agentv2.git.port.GitBranchRegistry;
import com.datagent.agentv2.persistence.entity.git.*;
import com.datagent.agentv2.persistence.service.git.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;

/** 独立工作区草稿门面；互斥覆盖读写、完整性检查及冻结，数据库更新不跨文件/Git I/O。 */
@RequiredArgsConstructor
public final class GitWorkspaceService {
    private static final String SHA_PATTERN = "[0-9a-f]{40}";
    private static final String PREVIEW = "PREVIEW";
    private static final String ACTIVE = "ACTIVE";
    private static final String MAIN_REF = "refs/heads/main";
    private static final String BRANCH_ID = "branchId";
    private static final String PROJECT_ID = "projectId";
    private static final String GIT_REF = "gitRef";
    private static final String MODE = "mode";
    private static final String STATUS = "status";
    private static final String CREATION_PREFIX = "creation:";
    private final GitWorkspacePersistenceService records;
    private final GitChangePersistenceService changes;
    private final GitRepositoryClient git;
    private final GitWorkspaceFiles files;
    private final GitProperties properties;
    private final GitApplicationProperties application;
    private final GitWorkspaceLocks locks;
    private final GitBranchRegistry branches;
    private final GitSessionWorkspaceService sessions;

    /** 仅保留原独立模块调用协议；运行时装配始终提供关联服务并要求 workspaceId。 */
    public GitWorkspaceService(
            GitWorkspacePersistenceService records,
            GitChangePersistenceService changes,
            GitRepositoryClient git,
            GitWorkspaceFiles files,
            GitProperties properties,
            GitApplicationProperties application,
            GitWorkspaceLocks locks) {
        this(records, changes, git, files, properties, application, locks, null, null);
    }

    /** 创建独立 clone；持久化意图先于 I/O，重试不重建、不覆盖未完成草稿。 */
    public GitWorkspaceView create(
            String sessionId, String branchId, String baseCommitSha, String idempotencyKey) {
        if (branches == null || sessions == null) {
            throw new GitException("GIT_NOT_CONFIGURED", "未配置分支开发服务");
        }
        if (sessionId == null
                || sessionId.isBlank()
                || branchId == null
                || branchId.isBlank()
                || baseCommitSha == null
                || !baseCommitSha.matches(SHA_PATTERN)
                || idempotencyKey == null
                || idempotencyKey.isBlank()
                || idempotencyKey.length() > 128) {
            throw new GitException("GIT_WORKSPACE_INVALID", "工作区创建参数无效");
        }
        String digest = creationDigest(branchId, baseCommitSha);
        return locked(
                CREATION_PREFIX + sessionId + idempotencyKey,
                () -> {
                    var existing = records.findCreationRequest(sessionId, idempotencyKey);
                    if (existing != null) {
                        if (!digest.equals(existing.getCreationDigest())) {
                            throw new GitException("GIT_CONFLICT", "同一创建键不能修改分支或基线");
                        }
                        sessions.attach(sessionId, existing.getWorkspaceId());
                        return GitWorkspaceView.composeFromWorkspace(existing);
                    }
                    var branch = branches.get(branchId);
                    if (!branchId.equals(branch.path(BRANCH_ID).asText())
                            || !PREVIEW.equals(branch.path(MODE).asText())
                            || !ACTIVE.equals(branch.path(STATUS).asText())
                            || branch.path(PROJECT_ID).asText().isBlank()
                            || branch.path(GIT_REF).asText().isBlank()
                            || MAIN_REF.equals(branch.path(GIT_REF).asText())) {
                        throw new GitException("GIT_CONFLICT", "分支尚未就绪或不能用于开发写入");
                    }
                    var w =
                            GitWorkspaceEntity.composeForCreation(
                                    sessionId,
                                    application.repositoryId(),
                                    branch.path(PROJECT_ID).asText(),
                                    branchId,
                                    branch.path(GIT_REF).asText(),
                                    baseCommitSha,
                                    idempotencyKey,
                                    digest);
                    // 唯一创建键保护跨进程重复请求；落库后失败只返回原工作区状态。
                    try {
                        records.save(w);
                    } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                        existing = records.findCreationRequest(sessionId, idempotencyKey);
                        if (existing == null || !digest.equals(existing.getCreationDigest())) {
                            throw new GitException("GIT_CONFLICT", "工作区创建请求已存在");
                        }
                        sessions.attach(sessionId, existing.getWorkspaceId());
                        return GitWorkspaceView.composeFromWorkspace(existing);
                    }
                    sessions.attach(sessionId, w.getWorkspaceId());
                    return locked(
                            w.getWorkspaceId(),
                            () -> {
                                initialize(w);
                                return get(w.getWorkspaceId());
                            });
                });
    }

    public GitWorkspaceView get(String workspaceId) {
        return GitWorkspaceView.composeFromWorkspace(record(workspaceId));
    }

    public List<GitWorkspaceView> listForSession(String sessionId) {
        return sessions.listForSession(sessionId).stream()
                .map(link -> get(link.getWorkspaceId()))
                .toList();
    }

    public GitWorkspaceView attach(String sessionId, String workspaceId) {
        var w = record(workspaceId);
        if (Boolean.TRUE.equals(w.getLegacyReadOnly())) {
            throw new GitException("GIT_CONFLICT", "历史生产工作区不能用于新的开发关联");
        }
        sessions.attach(sessionId, workspaceId);
        return GitWorkspaceView.composeFromWorkspace(w);
    }

    private static String creationDigest(String branchId, String baseCommitSha) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(branchId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return HexFormat.of()
                    .formatHex(digest.digest(baseCommitSha.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public GitWorkspaceView prepare(String sessionId, boolean restartDraft) {
        return prepare(sessionId, restartDraft, null);
    }

    /** 显式恢复无需执行绑定；预期修订阻止排队中的旧请求丢弃他人新草稿。 */
    public GitWorkspaceView prepare(String sessionId, boolean restartDraft, Long expectedRevision) {
        return locked(
                sessionId,
                () -> {
                    var w = lookup(sessionId);
                    if (w == null) {
                        if (sessions != null) {
                            throw new GitException("GIT_WORKSPACE_NOT_FOUND", "请先创建或选择明确工作区");
                        }
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
                        assertRevision(w, expectedRevision);
                        assertEditable(w);
                        assertBranchActive(w);
                        changes.invalidatePending(w.getWorkspaceId());
                        w.setActiveChangeId(null);
                        w.setStatus("MUTATING");
                        persist(w);
                        // 旧 clone 与候选文件保留作为归档；新草稿使用全新服务端键，绝不 reset 掉审阅证据。
                        w = lookup(sessionId);
                        w.setStorageKey("workspaces/" + UUID.randomUUID());
                        w.setDraftRevision(w.getDraftRevision() + 1);
                        persist(w);
                        initialize(lookup(sessionId));
                    } else {
                        ensureIntegrity(w);
                    }
                    return GitWorkspaceView.composeFromWorkspace(lookup(sessionId));
                });
    }

    private void initialize(GitWorkspaceEntity w) {
        try {
            if (w.getBranchId() == null) {
                git.cloneRepository(path(w));
            } else {
                git.cloneRepository(path(w), w.getGitRef(), w.getBaseCommit());
            }
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
                entries,
                w.getProjectId(),
                w.getBranchId(),
                w.getGitRef());
    }

    public GitWorkspaceView apply(String sessionId, List<FileOperation> operations) {
        return apply(sessionId, operations, null);
    }

    /** 分支工作区的每次编辑同时核对整体修订和每个文件摘要。 */
    public GitWorkspaceView apply(
            String sessionId, List<FileOperation> operations, Long expectedRevision) {
        return locked(
                sessionId,
                () -> {
                    var w = require(sessionId);
                    assertRevision(w, expectedRevision);
                    assertEditable(w);
                    String before = w.getDraftDigest();
                    // 写入前持久化屏障，崩溃后禁止把未登记的新内容当作旧草稿批准。
                    w.setStatus("MUTATING");
                    persist(w);
                    try {
                        files.apply(path(w), operations);
                    } finally {
                        var current = lookup(sessionId);
                        String after = fingerprint(path(current));
                        if (!before.equals(after)) {
                            changes.invalidatePending(current.getWorkspaceId());
                            current.setDraftRevision(current.getDraftRevision() + 1);
                        }
                        current.setDraftDigest(after);
                        current.setStatus("READY");
                        persist(current);
                    }
                    return GitWorkspaceView.composeFromWorkspace(lookup(sessionId));
                });
    }

    <T> T locked(String session, Supplier<T> action) {
        return locks.workspace(application.repositoryId(), session, action);
    }

    private void assertRevision(GitWorkspaceEntity workspace, Long expectedRevision) {
        if ((workspace.getBranchId() != null && expectedRevision == null)
                || (expectedRevision != null
                        && !expectedRevision.equals(workspace.getDraftRevision()))) {
            throw new GitException("GIT_CONFLICT", "必须提供当前草稿修订号，读取后重试");
        }
    }

    <T> T submitting(String session, Supplier<T> action) {
        return locks.repository(application.repositoryId(), () -> locked(session, action));
    }

    GitWorkspaceEntity require(String session) {
        var w = lookup(session);
        if (w == null) {
            throw new GitException("GIT_WORKSPACE_NOT_FOUND", "请先准备当前会话工作区");
        }
        ensureIntegrity(w);
        return w;
    }

    GitWorkspaceEntity record(String session) {
        var w = lookup(session);
        if (w == null) {
            throw new GitException("GIT_WORKSPACE_NOT_FOUND", "工作区不存在");
        }
        return w;
    }

    private GitWorkspaceEntity lookup(String target) {
        // 生产运行时只接受稳定工作区 ID，绝不回退到会话或 main。
        return sessions == null
                ? records.find(target, application.repositoryId())
                : records.findByWorkspaceId(target);
    }

    /** 卡片按钮必须跟随候选自身的工作区，而不是当前会话选择。 */
    String target(GitChangeEntity candidate) {
        return sessions == null ? candidate.getConversationSessionId() : candidate.getWorkspaceId();
    }

    /** 同步完成后替换目录指针；原 clone 留存，CAS 失败不会覆盖原工作目录。 */
    void acceptSync(GitWorkspaceEntity w, String storageKey, String remoteCommit) {
        changes.invalidatePending(w.getWorkspaceId());
        w.setStorageKey(storageKey);
        w.setBaseCommit(remoteCommit);
        w.setDraftDigest(fingerprint(path(w)));
        w.setDraftRevision(w.getDraftRevision() + 1);
        w.setActiveChangeId(null);
        persist(w);
    }

    /** 仅在未接管的新合并目录写入人工决议，当前草稿在成功 CAS 前保持不变。 */
    void resolveFiles(Path destination, Map<String, String> resolutions) {
        if (resolutions.size() > properties.maxCandidateFiles())
            throw new GitException("GIT_LIMIT_EXCEEDED", "冲突文件数超过服务端上限");
        var operations = new ArrayList<FileOperation>();
        resolutions.forEach(
                (name, content) -> {
                    var current = files.read(destination, name);
                    operations.add(
                            content == null
                                    ? FileOperation.delete(name, current.sha256())
                                    : FileOperation.update(name, current.sha256(), content));
                });
        files.apply(destination, operations);
        // 完整 freeze 再次检查文件数量/总字节/完整 diff，不能以人工解决绕过候选上限。
        try {
            git.freeze(destination);
        } catch (GitException failure) {
            if (!"GIT_EMPTY_CHANGE".equals(failure.code())) throw failure;
        }
    }

    void checkConflictSize(List<GitConflict> conflicts) {
        long bytes = 0;
        for (var conflict : conflicts) {
            for (var content :
                    Arrays.asList(
                            conflict.baseContent(),
                            conflict.localContent(),
                            conflict.remoteContent()))
                if (content != null) bytes += content.getBytes(StandardCharsets.UTF_8).length;
        }
        if (conflicts.size() > properties.maxCandidateFiles()
                || bytes > properties.maxCandidateBytes())
            throw new GitException("GIT_LIMIT_EXCEEDED", "冲突材料超过服务端上限");
    }

    /** 每次变更核对原分支实例，禁止删除后同名引用被旧工作区复用。 */
    void assertBranchActive(GitWorkspaceEntity w) {
        if (w.getBranchId() == null) return;
        if (branches == null) throw new GitException("GIT_NOT_CONFIGURED", "未配置分支服务");
        var branch = branches.get(w.getBranchId());
        if (!w.getBranchId().equals(branch.path(BRANCH_ID).asText())
                || !w.getGitRef().equals(branch.path(GIT_REF).asText())
                || !w.getProjectId().equals(branch.path(PROJECT_ID).asText())
                || !ACTIVE.equals(branch.path(STATUS).asText())
                || !PREVIEW.equals(branch.path(MODE).asText())) {
            throw new GitException("GIT_CONFLICT", "原分支实例已不可开发");
        }
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
        if (Boolean.TRUE.equals(w.getLegacyReadOnly())) {
            throw new GitException("GIT_CONFLICT", "历史生产工作区只允许读取恢复材料");
        }
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
        var fresh = records.findByWorkspaceId(w.getWorkspaceId());
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
        var fresh = records.findByWorkspaceId(w.getWorkspaceId());
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
