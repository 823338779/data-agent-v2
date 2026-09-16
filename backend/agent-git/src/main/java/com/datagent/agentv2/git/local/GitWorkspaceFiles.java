package com.datagent.agentv2.git.local;

import com.datagent.agentv2.git.config.GitProperties;
import com.datagent.agentv2.git.model.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** 在上层给定的工作区根目录内执行有界 UTF-8 普通文件操作。 */
public final class GitWorkspaceFiles {
    private final GitProperties properties;

    public GitWorkspaceFiles(GitProperties properties) {
        this.properties = properties;
    }

    public List<String> list(Path root, String relativeDirectory) {
        Path directory = resolve(root, relativeDirectory, false);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var paths = Files.walk(directory)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !containsGit(root.relativize(path)))
                    .map(
                            path ->
                                    root.toAbsolutePath()
                                            .normalize()
                                            .relativize(path.toAbsolutePath().normalize())
                                            .toString()
                                            .replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (Exception exception) {
            throw new GitException("GIT_FILE_IO", "无法列出工作区文件", exception);
        }
    }

    public FileReadResult read(Path root, String relativePath) {
        Path file = resolve(root, relativePath, true);
        byte[] bytes = readBytes(file);
        String content = decode(bytes);
        return new FileReadResult(relativePath.replace('\\', '/'), content, sha256(bytes));
    }

    public List<FileSearchMatch> search(Path root, String relativeDirectory, String query) {
        List<FileSearchMatch> matches = new ArrayList<>();
        for (String path : list(root, relativeDirectory)) {
            String[] lines = read(root, path).content().split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(query))
                    matches.add(new FileSearchMatch(path, i + 1, lines[i]));
            }
        }
        return List.copyOf(matches);
    }

    public void apply(Path root, List<FileOperation> operations) {
        if (operations == null
                || operations.isEmpty()
                || operations.size() > properties.maxFilesPerOperation()) {
            throw new GitException("GIT_LIMIT_EXCEEDED", "单次文件操作数量无效或超过上限");
        }
        Set<Path> targets = new HashSet<>();
        for (FileOperation operation : operations) {
            Path target =
                    resolve(root, operation.path(), operation.type() != FileOperation.Type.CREATE);
            if (!targets.add(target))
                throw new GitException("GIT_CONTENT_CONFLICT", "同一路径不能在一次请求中重复修改");
            boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (operation.type() == FileOperation.Type.CREATE) {
                if (exists) throw new GitException("GIT_CONTENT_CONFLICT", "新增文件已存在");
                validateContent(operation.content());
            } else {
                byte[] original = exists ? readBytes(target) : null;
                if (original != null) {
                    decode(original);
                }
                if (!exists || !Objects.equals(operation.expectedSha256(), sha256(original))) {
                    throw new GitException("GIT_CONTENT_CONFLICT", "文件摘要已变化");
                }
                if (operation.type() == FileOperation.Type.UPDATE)
                    validateContent(operation.content());
            }
        }
        // 全部预检成功后才落盘，避免摘要冲突造成半次请求写入。
        for (FileOperation operation : operations) {
            Path target =
                    resolve(root, operation.path(), operation.type() != FileOperation.Type.CREATE);
            try {
                if (operation.type() == FileOperation.Type.DELETE) {
                    Files.delete(target);
                } else {
                    Path parent = target.getParent();
                    validateParents(root, parent);
                    Files.createDirectories(parent);
                    if (operation.type() == FileOperation.Type.CREATE) {
                        Files.writeString(
                                target,
                                operation.content(),
                                StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE_NEW);
                    } else {
                        Files.writeString(
                                target,
                                operation.content(),
                                StandardCharsets.UTF_8,
                                StandardOpenOption.TRUNCATE_EXISTING);
                    }
                }
            } catch (FileAlreadyExistsException exception) {
                if (operation.type() == FileOperation.Type.UPDATE) {
                    try {
                        Files.writeString(
                                target,
                                operation.content(),
                                StandardCharsets.UTF_8,
                                StandardOpenOption.TRUNCATE_EXISTING);
                    } catch (Exception nested) {
                        throw new GitException("GIT_FILE_IO", "无法更新文件", nested);
                    }
                } else throw new GitException("GIT_CONTENT_CONFLICT", "新增文件已存在", exception);
            } catch (Exception exception) {
                throw new GitException("GIT_FILE_IO", "无法应用文件操作", exception);
            }
        }
    }

    private Path resolve(Path root, String relative, boolean requireFile) {
        if (relative == null || relative.isBlank() && requireFile)
            throw new GitException("GIT_PATH_REJECTED", "文件路径不能为空");
        Path supplied;
        try {
            supplied = Path.of(relative == null ? "" : relative);
        } catch (InvalidPathException exception) {
            throw new GitException("GIT_PATH_REJECTED", "文件路径无效");
        }
        if (supplied.isAbsolute()
                || containsGit(supplied)
                || supplied.normalize().startsWith("..")) {
            throw new GitException("GIT_PATH_REJECTED", "文件路径越过工作区边界");
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = normalizedRoot.resolve(supplied).normalize();
        if (!target.startsWith(normalizedRoot))
            throw new GitException("GIT_PATH_REJECTED", "文件路径越过工作区边界");
        validateParents(normalizedRoot, target.getParent());
        if (Files.isSymbolicLink(target)
                || requireFile
                        && Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new GitException("GIT_PATH_REJECTED", "只允许工作区内普通文件");
        }
        return target;
    }

    private static void validateParents(Path root, Path parent) {
        for (Path current = parent;
                current != null && current.startsWith(root);
                current = current.getParent()) {
            if (Files.isSymbolicLink(current))
                throw new GitException("GIT_PATH_REJECTED", "路径父目录不能是符号链接");
            if (current.equals(root)) break;
        }
    }

    private byte[] readBytes(Path file) {
        try {
            long size = Files.size(file);
            if (size > properties.maxFileBytes())
                throw new GitException("GIT_FILE_TOO_LARGE", "文件超过服务端上限");
            return Files.readAllBytes(file);
        } catch (GitException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GitException("GIT_FILE_IO", "无法读取文件", exception);
        }
    }

    private void validateContent(String content) {
        if (content == null) throw new GitException("GIT_BINARY_FILE", "文本内容不能为空");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > properties.maxFileBytes())
            throw new GitException("GIT_FILE_TOO_LARGE", "文件超过服务端上限");
        if (content.indexOf('\0') >= 0) throw new GitException("GIT_BINARY_FILE", "拒绝二进制内容");
    }

    private static String decode(byte[] bytes) {
        if (containsNul(bytes)) throw new GitException("GIT_BINARY_FILE", "拒绝二进制文件");
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new GitException("GIT_BINARY_FILE", "文件不是有效 UTF-8 文本", exception);
        }
    }

    private static boolean containsNul(byte[] bytes) {
        for (byte value : bytes) if (value == 0) return true;
        return false;
    }

    private static boolean containsGit(Path path) {
        for (Path part : path) if (part.toString().equalsIgnoreCase(".git")) return true;
        return false;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
