package com.datagent.agentv2.git.local;

import com.datagent.agentv2.git.config.GitProperties;
import com.datagent.agentv2.git.model.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** 固定远端与 main 分支的本地 Git 门面，不接受任意 Git 参数。 */
public final class GitRepositoryClient {
    private static final String HEADS_PREFIX = "refs/heads/";
    private static final String MAIN_REF = "refs/heads/main";
    private static final String CHECK_REF = "check-ref-format";
    private static final String MERGE_BASE = "merge-base";
    private static final String IS_ANCESTOR = "--is-ancestor";
    private static final String CHECKOUT = "checkout";
    private static final String DETACH = "--detach";
    private static final String HEAD = "HEAD";
    private static final String SHA_PATTERN = "[0-9a-f]{40}";
    private static final String FETCH = "fetch";
    private static final String NO_TAGS = "--no-tags";
    private static final String ORIGIN = "origin";
    private static final String REMOTE_PREFIX = "refs/remotes/origin/";
    private static final String REV_PARSE = "rev-parse";
    private static final String PUSH = "push";
    private static final String LEASE_PREFIX = "--force-with-lease=";
    private static final String REF_SEPARATOR = ":";
    private static final String MERGE_TREE = "merge-tree";
    private static final String WRITE_TREE = "--write-tree";
    private static final String NAME_ONLY = "--name-only";
    private static final String ZERO = "-z";
    private static final String MERGE_BASE_OPTION = "--merge-base=";
    private static final String SYNC_MESSAGE = "local draft merge input";
    private static final String SYNC_AUTHOR = "Agent workspace sync";
    private static final String SYNC_EMAIL = "workspace-sync@localhost";
    private static final String CLONE = "clone";
    private static final String NO_HARDLINKS = "--no-hardlinks";
    private static final String NO_CHECKOUT = "--no-checkout";
    private static final String END_OPTIONS = "--";
    private static final String READ_TREE = "read-tree";
    private static final String RESET_INDEX = "--reset";
    private static final String UPDATE_FILES = "-u";
    private static final String UPDATE_REF = "update-ref";
    private final GitProperties properties;
    private final GitCommandRunner runner;

    public GitRepositoryClient(GitProperties properties) {
        this.properties = properties;
        this.runner = new GitCommandRunner(properties);
    }

    public void cloneRepository(Path workspace) {
        cloneRepository(workspace, MAIN_REF, null);
    }

    /** 从登记引用独立克隆，再固定至已选基线；不会创建或推送远端分支。 */
    public void cloneRepository(Path workspace, String gitRef, String baseCommitSha) {
        if (gitRef == null
                || !gitRef.startsWith(HEADS_PREFIX)
                || (baseCommitSha != null && !baseCommitSha.matches(SHA_PATTERN))) {
            throw new GitException("GIT_WORKSPACE_INVALID", "分支引用或基线提交无效");
        }
        Path parent = workspace.toAbsolutePath().normalize().getParent();
        try {
            Files.createDirectories(parent);
        } catch (Exception exception) {
            throw new GitException("GIT_WORKSPACE_INVALID", "无法创建工作区父目录", exception);
        }
        runner.run(parent, List.of(CHECK_REF, gitRef));
        runner.run(
                parent,
                List.of(
                        "clone",
                        "--no-local",
                        "--origin",
                        "origin",
                        "--branch",
                        gitRef.substring(HEADS_PREFIX.length()),
                        "--single-branch",
                        "--",
                        properties.remoteUri(),
                        workspace.toString()));
        // 只允许该分支可达的明确基线，防止参数注入及错用其他分支提交。
        if (baseCommitSha != null) {
            runner.run(workspace, List.of(MERGE_BASE, IS_ANCESTOR, baseCommitSha, HEAD));
            runner.run(workspace, List.of(CHECKOUT, DETACH, baseCommitSha));
        }
    }

    public String fetchMain(Path workspace) {
        runner.run(
                workspace,
                List.of(
                        "fetch",
                        "--no-tags",
                        "origin",
                        "+refs/heads/main:refs/remotes/origin/main"));
        return text(runner.run(workspace, List.of("rev-parse", "refs/remotes/origin/main")));
    }

    /** 显式抓取原目标；引用被删除时失败，不沿用本地残留的 tracking ref。 */
    public String fetchBranch(Path workspace, String gitRef) {
        validateBranch(workspace, gitRef);
        String tracking = trackingRef(gitRef);
        runner.run(workspace, List.of(FETCH, NO_TAGS, ORIGIN, gitRef + REF_SEPARATOR + tracking));
        return text(runner.run(workspace, List.of(REV_PARSE, tracking)));
    }

    /** 固定 SHA 的包含关系；用于结果未知恢复，不能重新生成提交。 */
    public boolean containsCommit(Path workspace, String gitRef, String commitId) {
        validateBranch(workspace, gitRef);
        requireSha(commitId);
        var result =
                runner.runAllowFailure(
                        workspace,
                        List.of(MERGE_BASE, IS_ANCESTOR, commitId, trackingRef(gitRef)),
                        Map.of());
        if (result.exitCode() == 0) return true;
        if (result.exitCode() == 1) return false;
        throw new GitException("GIT_COMMAND_FAILED", "无法核实固定分支提交祖先关系");
    }

    /** 仅允许快进，同时要求远端仍为预期 SHA，关闭 fetch 与 push 之间的删除/变更窗口。 */
    public void pushCommit(Path workspace, String gitRef, String expectedHead, String commitId) {
        requireSha(expectedHead);
        requireSha(commitId);
        if (!expectedHead.equals(fetchBranch(workspace, gitRef))) {
            throw new GitException("GIT_NON_FAST_FORWARD", "远端分支已变化，需要同步并重新确认");
        }
        // lease 仅充当远端 CAS；先证明祖先关系，绝不利用该选项允许历史回退或覆盖。
        runner.run(workspace, List.of(MERGE_BASE, IS_ANCESTOR, expectedHead, commitId));
        var result =
                runner.runAllowFailure(
                        workspace,
                        List.of(
                                PUSH,
                                LEASE_PREFIX + gitRef + REF_SEPARATOR + expectedHead,
                                ORIGIN,
                                commitId + REF_SEPARATOR + gitRef),
                        Map.of());
        if (result.exitCode() != 0) {
            throw new GitException("GIT_NON_FAST_FORWARD", "远端拒绝快进或目标已删除，需要重新确认");
        }
    }

    private void validateBranch(Path workspace, String gitRef) {
        if (gitRef == null || !gitRef.startsWith(HEADS_PREFIX)) {
            throw new GitException("GIT_WORKSPACE_INVALID", "分支必须使用完整引用");
        }
        runner.run(workspace, List.of(CHECK_REF, gitRef));
    }

    private static void requireSha(String sha) {
        if (sha == null || !sha.matches(SHA_PATTERN)) {
            throw new GitException("GIT_WORKSPACE_INVALID", "提交必须使用完整 SHA");
        }
    }

    private static String trackingRef(String gitRef) {
        return REMOTE_PREFIX + gitRef.substring(HEADS_PREFIX.length());
    }

    /** 本地合并对象只用于保存三方材料，不是确认候选，也不会推送。 */
    public MergeResult mergeDraft(Path workspace, String base, String tree, String remote) {
        requireSha(base);
        requireSha(tree);
        requireSha(remote);
        String treeId = text(runner.run(workspace, List.of(REV_PARSE, tree + "^{tree}")));
        String draft =
                createCommit(
                        workspace,
                        treeId,
                        base,
                        SYNC_MESSAGE,
                        SYNC_AUTHOR,
                        SYNC_EMAIL,
                        Instant.ofEpochSecond(1_000_000_000));
        var result =
                runner.runAllowFailure(
                        workspace,
                        List.of(
                                MERGE_TREE,
                                WRITE_TREE,
                                NAME_ONLY,
                                ZERO,
                                MERGE_BASE_OPTION + base,
                                remote,
                                draft),
                        Map.of());
        if (result.exitCode() != 0 && result.exitCode() != 1) {
            throw new GitException("GIT_COMMAND_FAILED", "无法合并草稿与远端");
        }
        String[] sections = result.output().split("\u0000", -1);
        requireSha(sections[0]);
        List<String> conflicts = new ArrayList<>();
        if (result.exitCode() == 1) {
            for (int i = 1; i < sections.length && !sections[i].isEmpty(); i++)
                conflicts.add(sections[i]);
            if (conflicts.isEmpty()) throw new GitException("GIT_COMMAND_FAILED", "合并冲突缺少路径信息");
        }
        retainSnapshot(workspace, UUID.randomUUID().toString(), sections[0]);
        return new MergeResult(sections[0], List.copyOf(conflicts));
    }

    /** 在新目录物化合并结果；旧目录不执行 reset、checkout 或删除。 */
    public void materializeMerge(Path source, Path destination, String tree, String remote) {
        runner.run(
                source,
                List.of(
                        CLONE,
                        NO_HARDLINKS,
                        NO_CHECKOUT,
                        END_OPTIONS,
                        source.toAbsolutePath().toString(),
                        destination.toAbsolutePath().toString()));
        runner.run(destination, List.of(READ_TREE, RESET_INDEX, UPDATE_FILES, tree));
        runner.run(destination, List.of(UPDATE_REF, HEAD, remote));
        // 新 clone 的 origin 指向本地来源；恢复受控部署远端，避免后续推送到草稿目录。
        runner.run(destination, List.of("remote", "set-url", ORIGIN, properties.remoteUri()));
    }

    /** tree 为完整合并树；冲突路径只用于定位，原材料保留在原 clone 中。 */
    public record MergeResult(String tree, List<String> conflictPaths) {}

    public String head(Path workspace) {
        return text(runner.run(workspace, List.of("rev-parse", "HEAD")));
    }

    /** 远端已接受后同步本地基线，只移动引用和 index，不改写已审阅的工作文件。 */
    public void acceptSubmittedCommit(Path workspace, String commit) {
        runner.run(workspace, List.of("update-ref", "HEAD", commit));
        runner.run(workspace, List.of("read-tree", commit));
    }

    /** 冻结 tree 及全部可达对象必须完整，不能仅因 tree ID 存在就批准损坏快照。 */
    public void verifyTree(Path workspace, String tree) {
        runner.run(workspace, List.of("fsck", "--full", "--no-reflogs", "--no-dangling", tree));
    }

    /** 专用本地 ref 仅保活候选 tree，避免 GC 清理未确认对象；不创建 commit、不移动分支且从不推送该 namespace。 */
    public void retainSnapshot(Path workspace, String changeId, String tree) {
        if (!changeId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new GitException("GIT_SNAPSHOT_FAILED", "候选标识无效");
        }
        runner.run(workspace, List.of("update-ref", "refs/agent-candidates/" + changeId, tree, ""));
    }

    /** 恢复只读取 commit 对象，不创建提交；防止损坏的已登记 ID 指向其他已推送提交而误报成功。 */
    public void verifyCommit(
            Path workspace,
            String commit,
            String tree,
            String parent,
            String message,
            String authorName,
            String authorEmail,
            Instant time) {
        String identity = authorName + " <" + authorEmail + "> " + time.getEpochSecond() + " +0000";
        String expected =
                "tree "
                        + tree
                        + "\nparent "
                        + parent
                        + "\nauthor "
                        + identity
                        + "\ncommitter "
                        + identity
                        + "\n\n"
                        + message
                        + (message.endsWith("\n") ? "" : "\n");
        try {
            String actual = runner.run(workspace, List.of("cat-file", "commit", commit)).output();
            if (!expected.equals(actual)) {
                throw new GitException("GIT_COMMIT_CORRUPT", "提交对象与确认元信息不一致");
            }
        } catch (GitException failure) {
            throw new GitException("GIT_COMMIT_CORRUPT", "提交对象缺失或与确认元信息不一致", failure);
        }
    }

    /** 按已登记文件生成完整审阅 diff；路径必须按字面匹配，避免方括号等字符扩展到其他文件。 */
    public String diffFile(Path workspace, String base, String tree, String file) {
        return runner.run(
                        workspace,
                        List.of(
                                "--literal-pathspecs",
                                "diff-tree",
                                "--no-ext-diff",
                                "--no-color",
                                "--full-index",
                                "-p",
                                base,
                                tree,
                                "--",
                                file))
                .output();
    }

    public GitSnapshot freeze(Path workspace) {
        return freeze(workspace, "");
    }

    /** 冻结 tree，并把提交说明纳入候选摘要，防止确认后替换说明。 */
    public GitSnapshot freeze(Path workspace, String commitMessage) {
        Path index;
        try {
            index = Files.createTempFile(properties.workspaceRoot(), "git-index-", ".tmp");
            Files.deleteIfExists(index);
        } catch (Exception exception) {
            try {
                Files.createDirectories(properties.workspaceRoot());
                index = Files.createTempFile(properties.workspaceRoot(), "git-index-", ".tmp");
                Files.deleteIfExists(index);
            } catch (Exception nested) {
                throw new GitException("GIT_SNAPSHOT_FAILED", "无法创建候选临时索引", nested);
            }
        }
        Map<String, String> env = Map.of("GIT_INDEX_FILE", index.toString());
        try {
            runner.run(workspace, List.of("read-tree", "HEAD"), env);
            List<String> workspacePaths = scanWorkspaceFiles(workspace);
            Map<String, BaselineEntry> baselineEntries = readBaselineEntries(workspace);
            GitWorkspaceFiles files = new GitWorkspaceFiles(properties);
            for (String path : workspacePaths) {
                String rawBlob =
                        text(
                                runner.run(
                                        workspace,
                                        List.of("hash-object", "--no-filters", "--", path)));
                BaselineEntry baseline = baselineEntries.remove(path);
                if (baseline != null && baseline.objectId().equals(rawBlob)) {
                    continue;
                }
                if (baseline != null) {
                    validateBaselineText(workspace, baseline);
                }
                // 只校验实际新增或修改内容；未修改的基线二进制和大文件保持原 tree 条目。
                files.read(workspace, path);
                String blob =
                        text(
                                runner.run(
                                        workspace,
                                        List.of("hash-object", "-w", "--no-filters", "--", path)));
                runner.run(
                        workspace,
                        List.of(
                                "update-index",
                                "--add",
                                "--cacheinfo",
                                (baseline == null ? "100644" : baseline.mode())
                                        + ","
                                        + blob
                                        + ","
                                        + path),
                        env);
            }
            for (BaselineEntry deleted : baselineEntries.values()) {
                validateBaselineText(workspace, deleted);
                runner.run(
                        workspace, List.of("update-index", "--remove", "--", deleted.path()), env);
            }
            String tree = text(runner.run(workspace, List.of("write-tree"), env));
            String names =
                    runner.run(
                                    workspace,
                                    List.of(
                                            "diff-tree",
                                            "--no-commit-id",
                                            "--name-status",
                                            "-z",
                                            "-r",
                                            "HEAD",
                                            tree))
                            .output();
            List<GitFileChange> changes = parseChanges(names);
            if (changes.isEmpty()) {
                throw new GitException("GIT_EMPTY_CHANGE", "候选没有文件变更");
            }
            if (changes.size() > properties.maxCandidateFiles()) {
                throw new GitException("GIT_LIMIT_EXCEEDED", "候选文件数超过服务端上限");
            }
            String diff =
                    runner.run(
                                    workspace,
                                    List.of(
                                            "diff-tree",
                                            "--no-ext-diff",
                                            "--no-color",
                                            "--full-index",
                                            "-p",
                                            "HEAD",
                                            tree))
                            .output();
            byte[] diffBytes = diff.getBytes(StandardCharsets.UTF_8);
            if (diffBytes.length > properties.maxDiffBytes()) {
                throw new GitException("GIT_LIMIT_EXCEEDED", "候选完整 diff 超过服务端上限");
            }
            long candidateBytes =
                    changes.stream()
                            .mapToLong(
                                    change ->
                                            fileSizeAt(workspace, "HEAD", change.path())
                                                    + fileSizeAt(workspace, tree, change.path()))
                            .sum();
            if (candidateBytes > properties.maxCandidateBytes()) {
                throw new GitException("GIT_LIMIT_EXCEEDED", "候选新旧内容总量超过服务端上限");
            }
            String digestInput =
                    properties.remoteUri()
                            + "\nmain\n"
                            + head(workspace)
                            + "\n"
                            + tree
                            + "\n"
                            + commitMessage
                            + "\n"
                            + diff;
            return new GitSnapshot(tree, sha256(digestInput), diff, changes);
        } finally {
            try {
                Files.deleteIfExists(index);
            } catch (Exception ignored) {
                /* 临时索引残留不改变仓库引用。 */
            }
        }
    }

    public String createCommit(
            Path workspace,
            String tree,
            String parent,
            String message,
            String authorName,
            String authorEmail,
            Instant time) {
        String timestamp = time.getEpochSecond() + " +0000";
        Map<String, String> env =
                Map.of(
                        "GIT_AUTHOR_NAME",
                        authorName,
                        "GIT_AUTHOR_EMAIL",
                        authorEmail,
                        "GIT_AUTHOR_DATE",
                        timestamp,
                        "GIT_COMMITTER_NAME",
                        authorName,
                        "GIT_COMMITTER_EMAIL",
                        authorEmail,
                        "GIT_COMMITTER_DATE",
                        timestamp);
        String commit =
                text(
                        runner.run(
                                workspace,
                                List.of("commit-tree", tree, "-p", parent, "-m", message),
                                env));
        // 此方法只在授权后调用。返回并登记 commitId 前保活提交本身，tree 引用无法保护其父方向的 commit。
        // 名称由内容寻址 ID 固定，重复生成可幂等写入；首版永久保留，且 push 从不包含该本地 namespace。
        runner.run(workspace, List.of("update-ref", "refs/agent-submissions/" + commit, commit));
        return commit;
    }

    public void pushMain(Path workspace, String commit, String expectedParent) {
        fetchMain(workspace);
        String remoteHead =
                text(runner.run(workspace, List.of("rev-parse", "refs/remotes/origin/main")));
        if (!remoteHead.equals(expectedParent)) {
            throw new GitException("GIT_NON_FAST_FORWARD", "远端 main 已变化，需要重新确认候选");
        }
        var result =
                runner.runAllowFailure(
                        workspace,
                        List.of("push", "origin", commit + ":refs/heads/main"),
                        Map.of());
        if (result.exitCode() != 0) {
            throw new GitException("GIT_NON_FAST_FORWARD", "远端拒绝更新 main，需要重新确认候选");
        }
    }

    public boolean isAncestorOfRemoteMain(Path workspace, String commit) {
        var result =
                runner.runAllowFailure(
                        workspace,
                        List.of("merge-base", "--is-ancestor", commit, "refs/remotes/origin/main"),
                        Map.of());
        if (result.exitCode() == 0) {
            return true;
        }
        if (result.exitCode() == 1) {
            return false;
        }
        throw new GitException("GIT_COMMAND_FAILED", "无法核实远端提交祖先关系");
    }

    public String readTreeFile(Path workspace, String treeish, String path) {
        Path supplied;
        try {
            supplied = Path.of(path);
        } catch (InvalidPathException exception) {
            throw new GitException("GIT_PATH_REJECTED", "拒绝读取不安全的 tree 路径");
        }
        if (supplied.isAbsolute()
                || supplied.normalize().startsWith("..")
                || containsGit(supplied)) {
            throw new GitException("GIT_PATH_REJECTED", "拒绝读取不安全的 tree 路径");
        }
        return runner.run(workspace, List.of("show", treeish + ":" + path)).output();
    }

    /** 冲突允许新增/删除文件；先检查存在与大小，再返回受控文本。 */
    public String readConflictFile(Path workspace, String treeish, String path) {
        requireSha(treeish);
        var found = runner.run(workspace, List.of("ls-tree", "-z", treeish, "--", path)).output();
        if (found.isEmpty()) return null;
        if (fileSizeAt(workspace, treeish, path) > properties.maxFileBytes()) {
            throw new GitException("GIT_LIMIT_EXCEEDED", "冲突文件超过服务端上限");
        }
        return readTreeFile(workspace, treeish, path);
    }

    private long fileSizeAt(Path workspace, String tree, String path) {
        var result =
                runner.runAllowFailure(
                        workspace, List.of("ls-tree", "-z", "--long", tree, "--", path), Map.of());
        if (result.exitCode() != 0) {
            throw new GitException("GIT_SNAPSHOT_FAILED", "无法读取候选文件大小");
        }
        if (result.output().isEmpty()) {
            return 0;
        }
        int tab = result.output().indexOf('\t');
        if (tab < 0) {
            throw new GitException("GIT_SNAPSHOT_FAILED", "Git tree 文件元数据格式无效");
        }
        String[] metadata = result.output().substring(0, tab).trim().split("\\s+");
        try {
            return Long.parseLong(metadata[3]);
        } catch (RuntimeException exception) {
            throw new GitException("GIT_SNAPSHOT_FAILED", "Git tree 文件大小无效", exception);
        }
    }

    private static List<GitFileChange> parseChanges(String names) {
        if (names.isEmpty()) {
            return List.of();
        }
        String[] fields = names.split("\u0000", -1);
        List<GitFileChange> changes = new ArrayList<>();
        for (int index = 0; index + 1 < fields.length && !fields[index].isEmpty(); index += 2) {
            changes.add(new GitFileChange(fields[index], fields[index + 1]));
        }
        return List.copyOf(changes);
    }

    private List<String> scanWorkspaceFiles(Path workspace) {
        Path root = workspace.toAbsolutePath().normalize();
        try (var paths = Files.walk(root)) {
            List<Path> all = paths.toList();
            for (Path path : all) {
                Path relative = root.relativize(path);
                if (relative.getNameCount() == 0 || isRootGitMetadata(relative)) {
                    continue;
                }
                if (Files.isSymbolicLink(path)
                        || containsGit(relative)
                        || (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
                    throw new GitException("GIT_PATH_REJECTED", "候选包含链接、子模块或嵌套 .git");
                }
            }
            return all.stream()
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(root::relativize)
                    .filter(path -> !isRootGitMetadata(path))
                    .map(path -> path.toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (GitException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GitException("GIT_SNAPSHOT_FAILED", "无法扫描候选文件", exception);
        }
    }

    private Map<String, BaselineEntry> readBaselineEntries(Path workspace) {
        String output =
                runner.run(workspace, List.of("ls-tree", "-r", "-z", "--long", "HEAD")).output();
        Map<String, BaselineEntry> entries = new HashMap<>();
        for (String entry : output.split("\u0000", -1)) {
            if (entry.isEmpty()) {
                continue;
            }
            int tab = entry.indexOf('\t');
            if (tab < 0) {
                throw new GitException("GIT_SNAPSHOT_FAILED", "Git tree 条目格式无效");
            }
            String[] metadata = entry.substring(0, tab).trim().split("\\s+");
            if (metadata.length != 4) {
                throw new GitException("GIT_SNAPSHOT_FAILED", "Git tree 条目元数据无效");
            }
            if (metadata[0].equals("160000")) {
                throw new GitException("GIT_PATH_REJECTED", "候选不支持 Git 子模块");
            }
            String path = entry.substring(tab + 1);
            entries.put(
                    path,
                    new BaselineEntry(metadata[0], metadata[2], Long.parseLong(metadata[3]), path));
        }
        return entries;
    }

    private void validateBaselineText(Path workspace, BaselineEntry baseline) {
        if (baseline.size() > properties.maxFileBytes()) {
            throw new GitException("GIT_FILE_TOO_LARGE", "待修改的基线文件超过服务端上限");
        }
        GitCommandRunner.Result content =
                runner.run(workspace, List.of("cat-file", "blob", baseline.objectId()));
        if (!content.validUtf8() || content.output().indexOf('\0') >= 0) {
            throw new GitException("GIT_BINARY_FILE", "拒绝修改不支持的二进制基线文件");
        }
    }

    private static boolean isRootGitMetadata(Path relative) {
        return relative.getNameCount() > 0
                && relative.getName(0).toString().equalsIgnoreCase(".git");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String text(GitCommandRunner.Result result) {
        return result.output().trim();
    }

    private static boolean containsGit(Path path) {
        for (Path part : path) {
            if (part.toString().equalsIgnoreCase(".git")) {
                return true;
            }
        }
        return false;
    }

    private record BaselineEntry(String mode, String objectId, long size, String path) {}
}
