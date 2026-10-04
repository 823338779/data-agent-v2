package com.datagent.agentv2.git.application;

import com.datagent.agentv2.git.config.GitProperties;
import com.datagent.agentv2.git.local.GitRepositoryClient;
import com.datagent.agentv2.git.model.*;
import com.datagent.agentv2.persistence.entity.git.GitChangeEntity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;

/** 原子落盘的不可变审阅材料；摘要同时约束数据库冻结字段，缺失时拒绝重建。 */
@RequiredArgsConstructor
public final class GitSnapshotStore {
    private final GitProperties properties;

    /**
     * @param fileId 当前快照内从零开始的稳定文件序号，不是全局文件 ID
     * @param status Git name-status 变更码，如 A/M/D
     * @param path 工作区相对路径，属于该候选冻结 tree 的审阅范围
     * @param diff 单文件完整补丁；仅列表投影时为 null，落盘快照必须保留完整内容
     */
    public record SnapshotFile(String fileId, String status, String path, String diff) {}

    /** 完整快照补丁与同一候选的有序单文件审阅材料，二者共同参与快照完整性摘要。 */
    public record StoredSnapshot(String diff, List<SnapshotFile> files) {}

    void save(GitChangeEntity c, GitSnapshot snapshot, GitRepositoryClient git, Path workspace) {
        try {
            List<SnapshotFile> files = new ArrayList<>();
            for (int i = 0; i < snapshot.changes().size(); i++) {
                var change = snapshot.changes().get(i);
                files.add(
                        new SnapshotFile(
                                Integer.toString(i),
                                change.status(),
                                change.path(),
                                git.diffFile(
                                        workspace,
                                        c.getBaseCommit(),
                                        c.getTreeId(),
                                        change.path())));
            }
            byte[] bytes = encode(c, new StoredSnapshot(snapshot.diff(), List.copyOf(files)));
            c.setSnapshotDigest(sha256(bytes));
            Path destination = path(c.getSnapshotStorageKey());
            Files.createDirectories(destination.getParent());
            Path temporary = Files.createTempFile(destination.getParent(), "pending-", ".tmp");
            try {
                Files.write(temporary, bytes);
                // 不允许覆盖已登记快照；原子重命名失败时整体失败，不退化成可见的半文件。
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (Exception failure) {
            throw new GitException("GIT_SNAPSHOT_FAILED", "无法保存完整候选快照", failure);
        }
    }

    StoredSnapshot load(GitChangeEntity c) {
        try {
            Path path = path(c.getSnapshotStorageKey());
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) > properties.maxDiffBytes() * 3L + 1024 * 1024) {
                throw new IOException();
            }
            byte[] bytes = Files.readAllBytes(path);
            if (!sha256(bytes).equals(c.getSnapshotDigest())) {
                throw new IOException();
            }
            try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (!read(input).equals(metadata(c))) {
                    throw new IOException();
                }
                String diff = read(input);
                int count = input.readInt();
                if (count < 1
                        || count > properties.maxCandidateFiles()
                        || count != c.getFileCount()) {
                    throw new IOException();
                }
                List<SnapshotFile> files = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    files.add(new SnapshotFile(read(input), read(input), read(input), read(input)));
                }
                if (input.available() != 0) {
                    throw new IOException();
                }
                return new StoredSnapshot(diff, List.copyOf(files));
            }
        } catch (Exception failure) {
            throw new GitException("GIT_SNAPSHOT_CORRUPT", "候选快照缺失或完整性校验失败", failure);
        }
    }

    private byte[] encode(GitChangeEntity c, StoredSnapshot snapshot) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            write(out, metadata(c));
            write(out, snapshot.diff());
            out.writeInt(snapshot.files().size());
            for (var file : snapshot.files()) {
                write(out, file.fileId());
                write(out, file.status());
                write(out, file.path());
                write(out, file.diff());
            }
        }
        return bytes.toByteArray();
    }

    private String metadata(GitChangeEntity c) {
        // 长度编码防止提交说明中的换行与字段分隔符产生歧义。审计、状态和确认时间是可变字段，不参与冻结摘要。
        String base =
                List.of(
                                c.getChangeId(),
                                c.getWorkspaceId(),
                                c.getConversationSessionId(),
                                c.getRepositoryId(),
                                c.getBranch(),
                                c.getBaseCommit(),
                                c.getTreeId(),
                                c.getCandidateDigest(),
                                c.getSummary(),
                                c.getCommitMessage(),
                                c.getDraftRevision().toString(),
                                c.getAuthorName(),
                                c.getAuthorEmail(),
                                c.getPrepareRequestKey(),
                                c.getRequestDigest())
                        .stream()
                        .map(v -> v.length() + ":" + v)
                        .collect(java.util.stream.Collectors.joining());
        // 旧无凭证快照保持原格式；新凭证参与摘要，删除或替换凭证都会使快照核对失败。
        if (c.getBranchId() != null) {
            base +=
                    List.of(c.getProjectId(), c.getBranchId(), c.getGitRef()).stream()
                            .map(value -> value.length() + ":" + value)
                            .collect(java.util.stream.Collectors.joining());
        }
        return c.getValidationEvidence() == null
                ? base
                : base + c.getValidationEvidence().length() + ":" + c.getValidationEvidence();
    }

    private Path path(String key) {
        Path root = properties.workspaceRoot().toAbsolutePath().normalize();
        Path value = root.resolve(key).normalize();
        if (!value.startsWith(root) || value.equals(root)) {
            throw new GitException("GIT_SNAPSHOT_CORRUPT", "候选存储键无效");
        }
        for (Path p = value; p != null && p.startsWith(root); p = p.getParent()) {
            if (Files.isSymbolicLink(p)) {
                throw new GitException("GIT_SNAPSHOT_CORRUPT", "候选存储路径无效");
            }
        }
        return value;
    }

    private static void write(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private String read(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > properties.maxDiffBytes() + 1024 * 1024) {
            throw new IOException();
        }
        byte[] bytes = in.readNBytes(count);
        if (bytes.length != count) {
            throw new IOException();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
