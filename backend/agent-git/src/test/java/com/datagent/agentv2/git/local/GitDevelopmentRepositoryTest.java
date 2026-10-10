package com.datagent.agentv2.git.local;

import static org.assertj.core.api.Assertions.*;

import com.datagent.agentv2.git.config.GitProperties;
import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** 临时 bare remote 验证真实分支、固定合并输入与响应丢失后的证据。 */
class GitDevelopmentRepositoryTest {
    @TempDir Path root;
    Path remote;
    Path seed;
    GitRepositoryClient git;
    String base;

    @BeforeEach
    void setup() throws Exception {
        remote = root.resolve("remote.git");
        run(root, "init", "--bare", "--initial-branch=main", remote.toString());
        seed = root.resolve("seed");
        run(root, "clone", remote.toString(), seed.toString());
        Files.writeString(seed.resolve("dbt_project.yml"), "name: example\n");
        commit("initial");
        run(seed, "push", "origin", "main");
        base = run(seed, "rev-parse", "HEAD");
        git =
                new GitRepositoryClient(
                        new GitProperties(
                                "git",
                                remote.toString(),
                                root.resolve("work"),
                                Duration.ofSeconds(20),
                                2_000_000,
                                1_000_000,
                                100,
                                100,
                                2_000_000,
                                2_000_000));
    }

    @Test
    void branchRecreationIsExplicit() {
        var branch = git.createBranch(remote.toString(), "feature/shared", base);
        assertThat(branch.headSha()).isEqualTo(base);
        assertThat(git.listBranches(remote.toString()))
                .extracting(b -> b.branchName())
                .containsExactly("feature/shared", "main");
        run(seed, "push", "origin", ":refs/heads/feature/shared");
        assertThatThrownBy(() -> git.verifyPush(remote.toString(), "feature/shared", base))
                .isInstanceOf(com.datagent.agentv2.git.model.GitException.class);
        assertThat(git.listBranches(remote.toString())).hasSize(1);
    }

    @Test
    void candidateNotReceivedIsNotVerified() {
        git.createBranch(remote.toString(), "feature/shared", base);
        assertThat(git.verifyPush(remote.toString(), "feature/shared", "b".repeat(40)).verified())
                .isFalse();
    }

    @Test
    void lostMergeResponseDoesNotMergeTwice() throws Exception {
        git.createBranch(remote.toString(), "feature/shared", base);
        Files.writeString(seed.resolve("macros.sql"), "select 1\n");
        commit("feature");
        String source = run(seed, "rev-parse", "HEAD");
        run(seed, "push", "origin", "HEAD:refs/heads/feature/shared");
        var candidate = git.prepareMerge(remote.toString(), "feature/shared", source, base);
        var pushed = git.pushMerge(candidate);
        var recovered = git.verifyMerge(remote.toString(), source, base, candidate.candidateSha());
        assertThat(recovered.mergeSha())
                .isEqualTo(pushed.mergeSha())
                .isEqualTo(candidate.candidateSha());
        assertThat(recovered.verified()).isTrue();
        assertThatThrownBy(
                        () -> git.prepareMerge(remote.toString(), "feature/shared", source, base))
                .isInstanceOf(com.datagent.agentv2.git.model.GitException.class);
    }

    @Test
    void advancedMainCannotProveExactDelivery() throws Exception {
        git.createBranch(remote.toString(), "feature/shared", base);
        Files.writeString(seed.resolve("metric.yml"), "invalid: [\n");
        commit("invalid yaml still saves");
        String source = run(seed, "rev-parse", "HEAD");
        run(seed, "push", "origin", "HEAD:refs/heads/feature/shared");
        assertThat(git.verifyPush(remote.toString(), "feature/shared", source).verified()).isTrue();
        var candidate = git.prepareMerge(remote.toString(), "feature/shared", source, base);
        git.pushMerge(candidate);
        run(seed, "fetch", "origin", "main");
        run(seed, "reset", "--hard", "origin/main");
        Files.writeString(seed.resolve("metric.yml"), "reverted\n");
        commit("later change");
        run(seed, "push", "origin", "main");
        assertThat(
                        git.verifyMerge(remote.toString(), source, base, candidate.candidateSha())
                                .verified())
                .isFalse();
    }

    void commit(String message) {
        run(seed, "add", ".");
        run(
                seed,
                "-c",
                "user.name=test",
                "-c",
                "user.email=test@example.test",
                "commit",
                "-m",
                message);
    }

    static String run(Path path, String... arguments) {
        try {
            var command = new java.util.ArrayList<String>();
            command.add("git");
            command.addAll(java.util.List.of(arguments));
            var process =
                    new ProcessBuilder(command)
                            .directory(path.toFile())
                            .redirectErrorStream(true)
                            .start();
            String result =
                    new String(
                            process.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
            if (process.waitFor() != 0) throw new IllegalStateException(result);
            return result.trim();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
