package com.datagent.agentv2.git.local;

import static org.junit.jupiter.api.Assertions.*;

import com.datagent.agentv2.git.config.GitProperties;
import com.datagent.agentv2.git.model.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class GitWorkspaceFilesTest {
    @TempDir Path root;
    private GitWorkspaceFiles files;

    @BeforeEach
    void setUp() throws Exception {
        files =
                new GitWorkspaceFiles(
                        new GitProperties(
                                "git",
                                "unused",
                                root,
                                Duration.ofSeconds(10),
                                1024 * 1024,
                                16,
                                20,
                                100,
                                1024,
                                1024));
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve("metrics.yml"), "old\n");
    }

    @Test
    void appliesCreateUpdateDeleteWithOptimisticDigest() throws Exception {
        String digest = files.read(root, "metrics.yml").sha256();
        files.apply(
                root,
                List.of(
                        FileOperation.update("metrics.yml", digest, "new\n"),
                        FileOperation.create("new.txt", "hello\n")));
        assertEquals("new\n", files.read(root, "metrics.yml").content());
        assertEquals(List.of("metrics.yml", "new.txt"), files.list(root, ""));
        assertEquals(1, files.search(root, "", "hello").size());
        files.apply(
                root,
                List.of(
                        FileOperation.delete(
                                "metrics.yml", files.read(root, "metrics.yml").sha256())));
        assertFalse(Files.exists(root.resolve("metrics.yml")));
    }

    @Test
    void rejectsTraversalGitLinksBinaryOversizeAndDigestConflict() throws Exception {
        assertCode("GIT_PATH_REJECTED", () -> files.read(root, "../outside"));
        assertCode("GIT_PATH_REJECTED", () -> files.read(root, ".git/config"));
        Path outside = root.getParent().resolve("outside.txt");
        Files.writeString(outside, "outside");
        try {
            Files.createSymbolicLink(root.resolve("link.txt"), outside);
            assertCode("GIT_PATH_REJECTED", () -> files.read(root, "link.txt"));
        } catch (UnsupportedOperationException | FileSystemException ignored) {
            // 当前文件系统不支持测试链接；其他边界仍需验证。
        }
        Files.write(root.resolve("binary.dat"), new byte[] {0, 1, 2});
        assertCode("GIT_BINARY_FILE", () -> files.read(root, "binary.dat"));
        Files.writeString(root.resolve("large.txt"), "12345678901234567");
        assertCode("GIT_FILE_TOO_LARGE", () -> files.read(root, "large.txt"));
        assertCode(
                "GIT_CONTENT_CONFLICT",
                () ->
                        files.apply(
                                root,
                                List.of(FileOperation.update("metrics.yml", "stale", "new"))));
    }

    @Test
    void createRequiresMissingPathAndOperationCountIsBounded() {
        assertCode(
                "GIT_CONTENT_CONFLICT",
                () -> files.apply(root, List.of(FileOperation.create("metrics.yml", "new"))));
        var tooMany =
                java.util.stream.IntStream.range(0, 21)
                        .mapToObj(i -> FileOperation.create("f" + i, "x"))
                        .toList();
        assertCode("GIT_LIMIT_EXCEEDED", () -> files.apply(root, tooMany));
    }

    private static void assertCode(String code, Executable action) {
        GitException error = assertThrows(GitException.class, action);
        assertEquals(code, error.code());
    }
}
