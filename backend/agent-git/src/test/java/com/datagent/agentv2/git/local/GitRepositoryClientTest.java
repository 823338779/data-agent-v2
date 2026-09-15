package com.datagent.agentv2.git.local;

import static org.junit.jupiter.api.Assertions.*;

import com.datagent.agentv2.git.config.GitProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class GitRepositoryClientTest {
    @TempDir Path temp;
    private Path remote;
    private GitRepositoryClient repository;

    @BeforeEach
    void setUp() throws Exception {
        remote = temp.resolve("remote.git");
        run(temp, "git", "init", "--bare", "--initial-branch=main", remote.toString());
        Path seed = temp.resolve("seed");
        run(temp, "git", "clone", remote.toString(), seed.toString());
        Files.writeString(seed.resolve("metrics.yml"), "old\n");
        run(seed, "git", "add", "metrics.yml");
        run(
                seed,
                "git",
                "-c",
                "user.name=seed",
                "-c",
                "user.email=seed@example.test",
                "commit",
                "-m",
                "seed");
        run(seed, "git", "push", "origin", "main");
        repository = new GitRepositoryClient(properties());
    }

    @Test
    void freezeWritesImmutableTreeWithoutMovingHead() throws Exception {
        Path workspace = temp.resolve("workspace");
        repository.cloneRepository(workspace);
        String baseCommit = repository.head(workspace);
        Files.writeString(workspace.resolve("metrics.yml"), "new\n", StandardCharsets.UTF_8);

        var snapshot = repository.freeze(workspace);

        assertEquals(baseCommit, repository.head(workspace));
        assertEquals("new\n", repository.readTreeFile(workspace, snapshot.treeId(), "metrics.yml"));
        assertEquals("old\n", repository.readTreeFile(workspace, baseCommit, "metrics.yml"));
        assertTrue(snapshot.diff().contains("+new"));
        assertFalse(snapshot.candidateDigest().isBlank());
    }

    @Test
    void deterministicCommitAndRemoteAncestorVerificationIncludeLaterCommit() throws Exception {
        Path workspace = temp.resolve("workspace");
        repository.cloneRepository(workspace);
        String base = repository.head(workspace);
        Files.writeString(workspace.resolve("metrics.yml"), "new\n");
        String tree = repository.freeze(workspace).treeId();
        Instant time = Instant.parse("2026-09-16T08:00:00Z");
        String first =
                repository.createCommit(
                        workspace,
                        tree,
                        base,
                        "update metrics",
                        "Agent",
                        "agent@example.test",
                        time);
        String second =
                repository.createCommit(
                        workspace,
                        tree,
                        base,
                        "update metrics",
                        "Agent",
                        "agent@example.test",
                        time);
        assertEquals(first, second);
        repository.pushMain(workspace, first, base);
        assertTrue(repository.isAncestorOfRemoteMain(workspace, first));

        Path other = temp.resolve("other");
        repository.cloneRepository(other);
        Files.writeString(other.resolve("later.txt"), "later\n");
        run(other, "git", "add", "later.txt");
        run(
                other,
                "git",
                "-c",
                "user.name=other",
                "-c",
                "user.email=other@example.test",
                "commit",
                "-m",
                "later");
        run(other, "git", "push", "origin", "main");
        repository.fetchMain(workspace);
        assertTrue(repository.isAncestorOfRemoteMain(workspace, first));
    }

    @Test
    void stalePushIsRejectedWithoutForce() throws Exception {
        Path one = temp.resolve("one");
        Path two = temp.resolve("two");
        repository.cloneRepository(one);
        repository.cloneRepository(two);
        String base = repository.head(one);
        String oneCommit = commitChange(one, base, "one.txt");
        String twoCommit = commitChange(two, base, "two.txt");
        repository.pushMain(one, oneCommit, base);
        var error =
                assertThrows(
                        com.datagent.agentv2.git.model.GitException.class,
                        () -> repository.pushMain(two, twoCommit, base));
        assertEquals("GIT_NON_FAST_FORWARD", error.code());
    }

    @Test
    void commandOutputLimitHasStableFailureCode() {
        GitProperties limited =
                new GitProperties(
                        "git",
                        remote.toString(),
                        temp.resolve("limited"),
                        Duration.ofSeconds(10),
                        1,
                        512 * 1024,
                        20,
                        100,
                        5 * 1024 * 1024,
                        2 * 1024 * 1024);
        var error =
                assertThrows(
                        com.datagent.agentv2.git.model.GitException.class,
                        () -> new GitCommandRunner(limited).run(temp, List.of("--version")));
        assertEquals("GIT_OUTPUT_TOO_LARGE", error.code());
    }

    @Test
    void candidateDigestBindsCommitMessage() throws Exception {
        Path workspace = temp.resolve("workspace");
        repository.cloneRepository(workspace);
        Files.writeString(workspace.resolve("metrics.yml"), "new\n");

        var first = repository.freeze(workspace, "message one");
        var second = repository.freeze(workspace, "message two");

        assertEquals(first.treeId(), second.treeId());
        assertNotEquals(first.candidateDigest(), second.candidateDigest());
    }

    @Test
    void freezePreservesUnicodePathIgnoredFileAndCrlfDespiteAttributes() throws Exception {
        Path workspace = temp.resolve("workspace");
        repository.cloneRepository(workspace);
        Files.writeString(workspace.resolve(".gitattributes"), "*.txt text eol=lf\n");
        Files.writeString(workspace.resolve(".gitignore"), "ignored.txt\n");
        Files.writeString(workspace.resolve("指标.txt"), "第一行\r\n第二行\r\n");
        Files.writeString(workspace.resolve("ignored.txt"), "ignored\r\n");

        var snapshot = repository.freeze(workspace);

        assertTrue(snapshot.changes().stream().anyMatch(change -> change.path().equals("指标.txt")));
        assertTrue(
                snapshot.changes().stream()
                        .anyMatch(change -> change.path().equals("ignored.txt")));
        assertEquals(
                "第一行\r\n第二行\r\n", repository.readTreeFile(workspace, snapshot.treeId(), "指标.txt"));
        assertEquals(
                "ignored\r\n",
                repository.readTreeFile(workspace, snapshot.treeId(), "ignored.txt"));
        assertEquals(
                "GIT_LIMIT_EXCEEDED",
                assertThrows(
                                com.datagent.agentv2.git.model.GitException.class,
                                () ->
                                        new GitRepositoryClient(properties(100, 1, 1024 * 1024))
                                                .freeze(workspace))
                        .code());
    }

    @Test
    void freezeEnforcesCandidateFileByteAndDiffLimits() throws Exception {
        Path workspace = temp.resolve("workspace");
        repository.cloneRepository(workspace);
        Files.writeString(workspace.resolve("one.txt"), "one\n");
        Files.writeString(workspace.resolve("two.txt"), "two\n");

        assertEquals(
                "GIT_LIMIT_EXCEEDED",
                assertThrows(
                                com.datagent.agentv2.git.model.GitException.class,
                                () ->
                                        new GitRepositoryClient(properties(1, 1024, 1024))
                                                .freeze(workspace))
                        .code());
        assertEquals(
                "GIT_LIMIT_EXCEEDED",
                assertThrows(
                                com.datagent.agentv2.git.model.GitException.class,
                                () ->
                                        new GitRepositoryClient(properties(100, 1, 1024))
                                                .freeze(workspace))
                        .code());
        assertEquals(
                "GIT_LIMIT_EXCEEDED",
                assertThrows(
                                com.datagent.agentv2.git.model.GitException.class,
                                () ->
                                        new GitRepositoryClient(properties(100, 1024, 1))
                                                .freeze(workspace))
                        .code());
    }

    @Test
    void interruptTerminatesBlockedGitProcess() throws Exception {
        AtomicReference<com.datagent.agentv2.git.model.GitException> failure =
                new AtomicReference<>();
        Thread thread =
                new Thread(
                        () -> {
                            try {
                                new GitCommandRunner(properties())
                                        .run(temp, List.of("hash-object", "--stdin"));
                            } catch (com.datagent.agentv2.git.model.GitException exception) {
                                failure.set(exception);
                            }
                        });
        thread.start();
        Thread.sleep(300);
        thread.interrupt();
        thread.join(3000);

        assertFalse(thread.isAlive());
        assertNotNull(failure.get());
        assertEquals("GIT_INTERRUPTED", failure.get().code());
    }

    @Test
    void inheritedConfigParametersCannotInjectGitConfiguration() {
        var environment = new HashMap<String, String>();
        environment.put("GIT_CONFIG_PARAMETERS", "'core.sshCommand'='malicious-helper'");

        GitCommandRunner.removeGitOverrides(environment);

        assertFalse(environment.containsKey("GIT_CONFIG_PARAMETERS"));
    }

    @Test
    void unchangedBinaryAndOversizedBaselineFilesDoNotBlockTextSnapshot() throws Exception {
        Path workspace = temp.resolve("workspace");
        repository.cloneRepository(workspace);
        Files.write(workspace.resolve("chart.png"), new byte[] {0, 1, 2, 3});
        Files.writeString(workspace.resolve("large.txt"), "x".repeat(600 * 1024));
        run(workspace, "git", "add", "chart.png", "large.txt");
        run(
                workspace,
                "git",
                "-c",
                "user.name=seed",
                "-c",
                "user.email=seed@example.test",
                "commit",
                "-m",
                "baseline assets");
        Files.writeString(workspace.resolve("metrics.yml"), "new\n");

        var snapshot = repository.freeze(workspace);

        assertEquals(
                List.of("metrics.yml"),
                snapshot.changes().stream().map(change -> change.path()).toList());
    }

    @Test
    void deletingBinaryBaselineFileIsRejected() throws Exception {
        Path workspace = temp.resolve("workspace");
        repository.cloneRepository(workspace);
        Files.write(workspace.resolve("chart.png"), new byte[] {0, 1, 2, 3});
        run(workspace, "git", "add", "chart.png");
        run(
                workspace,
                "git",
                "-c",
                "user.name=seed",
                "-c",
                "user.email=seed@example.test",
                "commit",
                "-m",
                "binary baseline");
        Files.delete(workspace.resolve("chart.png"));

        var error =
                assertThrows(
                        com.datagent.agentv2.git.model.GitException.class,
                        () -> repository.freeze(workspace));
        assertEquals("GIT_BINARY_FILE", error.code());
    }

    @Test
    void replacingBinaryBaselineFileWithUtf8TextIsRejected() throws Exception {
        Path workspace = temp.resolve("workspace");
        repository.cloneRepository(workspace);
        Files.write(workspace.resolve("chart.png"), new byte[] {0, 1, 2, 3});
        run(workspace, "git", "add", "chart.png");
        run(
                workspace,
                "git",
                "-c",
                "user.name=seed",
                "-c",
                "user.email=seed@example.test",
                "commit",
                "-m",
                "binary baseline");
        Files.writeString(workspace.resolve("chart.png"), "text\n");

        var error =
                assertThrows(
                        com.datagent.agentv2.git.model.GitException.class,
                        () -> repository.freeze(workspace));
        assertEquals("GIT_BINARY_FILE", error.code());
    }

    private String commitChange(Path workspace, String base, String file) throws Exception {
        Files.writeString(workspace.resolve(file), file + "\n");
        String tree = repository.freeze(workspace).treeId();
        return repository.createCommit(
                workspace,
                tree,
                base,
                file,
                "Agent",
                "agent@example.test",
                Instant.parse("2026-09-16T08:00:00Z"));
    }

    private GitProperties properties() {
        return properties(100, 5 * 1024 * 1024, 2 * 1024 * 1024);
    }

    private GitProperties properties(
            int maxCandidateFiles, long maxCandidateBytes, int maxDiffBytes) {
        return new GitProperties(
                "git",
                remote.toString(),
                temp.resolve("workspaces"),
                Duration.ofSeconds(10),
                1024 * 1024,
                512 * 1024,
                20,
                maxCandidateFiles,
                maxCandidateBytes,
                maxDiffBytes);
    }

    private static void run(Path directory, String... command) throws Exception {
        Process process =
                new ProcessBuilder(command)
                        .directory(directory.toFile())
                        .redirectErrorStream(true)
                        .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }
}
