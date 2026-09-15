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
    private final GitProperties properties;
    private final GitCommandRunner runner;

    public GitRepositoryClient(GitProperties properties) {
        this.properties = properties;
        this.runner = new GitCommandRunner(properties);
    }

    public void cloneRepository(Path workspace) {
        Path parent = workspace.toAbsolutePath().normalize().getParent();
        try {
            Files.createDirectories(parent);
        } catch (Exception exception) {
            throw new GitException("GIT_WORKSPACE_INVALID", "无法创建工作区父目录", exception);
        }
        runner.run(
                parent,
                List.of(
                        "clone",
                        "--no-local",
                        "--origin",
                        "origin",
                        "--branch",
                        "main",
                        "--single-branch",
                        "--",
                        properties.remoteUri(),
                        workspace.toString()));
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

    public String head(Path workspace) {
        return text(runner.run(workspace, List.of("rev-parse", "HEAD")));
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
            runner.run(workspace, List.of("read-tree", "--empty"), env);
            List<String> workspacePaths = scanWorkspaceFiles(workspace);
            Map<String, String> baselineModes = readBaselineModes(workspace);
            GitWorkspaceFiles files = new GitWorkspaceFiles(properties);
            for (String path : workspacePaths) {
                // hash-object 默认不应用 clean/eol/working-tree-encoding 过滤器，tree 保留审阅时原始字节。
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
                                baselineModes.getOrDefault(path, "100644")
                                        + ","
                                        + blob
                                        + ","
                                        + path),
                        env);
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
        return text(
                runner.run(
                        workspace, List.of("commit-tree", tree, "-p", parent, "-m", message), env));
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
        if (result.exitCode() == 0) return true;
        if (result.exitCode() == 1) return false;
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

    private long fileSizeAt(Path workspace, String tree, String path) {
        var result =
                runner.runAllowFailure(
                        workspace, List.of("ls-tree", "-z", "--long", tree, "--", path), Map.of());
        if (result.exitCode() != 0) {
            throw new GitException("GIT_SNAPSHOT_FAILED", "无法读取候选文件大小");
        }
        if (result.output().isEmpty()) return 0;
        int tab = result.output().indexOf('\t');
        if (tab < 0) throw new GitException("GIT_SNAPSHOT_FAILED", "Git tree 文件元数据格式无效");
        String[] metadata = result.output().substring(0, tab).trim().split("\\s+");
        try {
            return Long.parseLong(metadata[3]);
        } catch (RuntimeException exception) {
            throw new GitException("GIT_SNAPSHOT_FAILED", "Git tree 文件大小无效", exception);
        }
    }

    private static List<GitFileChange> parseChanges(String names) {
        if (names.isEmpty()) return List.of();
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
                if (relative.getNameCount() == 0 || isRootGitMetadata(relative)) continue;
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

    private Map<String, String> readBaselineModes(Path workspace) {
        String output = runner.run(workspace, List.of("ls-tree", "-r", "-z", "HEAD")).output();
        Map<String, String> modes = new HashMap<>();
        for (String entry : output.split("\u0000", -1)) {
            if (entry.isEmpty()) continue;
            int tab = entry.indexOf('\t');
            if (tab < 0) throw new GitException("GIT_SNAPSHOT_FAILED", "Git tree 条目格式无效");
            String[] metadata = entry.substring(0, tab).split(" ");
            if (metadata.length != 3) {
                throw new GitException("GIT_SNAPSHOT_FAILED", "Git tree 条目元数据无效");
            }
            if (metadata[0].equals("160000")) {
                throw new GitException("GIT_PATH_REJECTED", "候选不支持 Git 子模块");
            }
            modes.put(entry.substring(tab + 1), metadata[0]);
        }
        return modes;
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
        for (Path part : path) if (part.toString().equalsIgnoreCase(".git")) return true;
        return false;
    }
}
