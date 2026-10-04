package com.datagent.agentv2.git.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.datagent.agentv2.git.config.*;
import com.datagent.agentv2.git.local.*;
import com.datagent.agentv2.git.model.*;
import com.datagent.agentv2.git.port.GitBranchRegistry;
import com.datagent.agentv2.persistence.*;
import com.datagent.agentv2.persistence.service.git.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** 真实 PostgreSQL 与临时远端验证分支并发、同步和固定目标恢复。 */
@SpringBootTest(classes = GitBranchLifecycleTest.Config.class)
class GitBranchLifecycleTest {
    static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17-alpine");

    static {
        DB.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Configuration
    @EnableAutoConfiguration
    @Import({
        MybatisPlusConfiguration.class,
        AuditMetaObjectHandler.class,
        GitWorkspacePersistenceService.class,
        GitChangePersistenceService.class,
        GitSessionWorkspaceService.class
    })
    static class Config {}

    @Autowired GitWorkspacePersistenceService workspaceRecords;
    @Autowired GitChangePersistenceService changeRecords;
    @Autowired GitSessionWorkspaceService sessions;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path temp;
    GitWorkspaceService workspaces;
    GitChangeService changes;
    GitSubmissionService submissions;
    GitWorkspaceSyncService sync;
    GitRepositoryClient git;
    Path seed;
    String base;
    ArrayDeque<Runnable> queue;

    @BeforeEach
    void setup() throws Exception {
        jdbc.execute("truncate table git_change, git_workspace restart identity cascade");
        Path remote = temp.resolve("remote.git");
        run(temp, "init", "--bare", "--initial-branch=main", remote.toString());
        seed = temp.resolve("seed");
        run(temp, "clone", remote.toString(), seed.toString());
        Files.writeString(seed.resolve("metrics.yml"), "metric: original\n");
        Files.writeString(seed.resolve("dimensions.yml"), "dimension: original\n");
        run(seed, "add", ".");
        run(
                seed,
                "-c",
                "user.name=test",
                "-c",
                "user.email=test@example.test",
                "commit",
                "-m",
                "seed");
        base = run(seed, "rev-parse", "HEAD");
        run(seed, "push", "origin", "HEAD:main", "HEAD:dev/a", "HEAD:dev/b");
        var properties =
                new GitProperties(
                        "git",
                        remote.toString(),
                        temp.resolve("work"),
                        Duration.ofSeconds(15),
                        4194304,
                        524288,
                        20,
                        100,
                        5242880,
                        2097152);
        var application = new GitApplicationProperties("metrics", "Agent", "agent@example.test");
        var registry = mock(GitBranchRegistry.class);
        for (String branch : List.of("a", "b")) {
            when(registry.get(branch))
                    .thenReturn(
                            new ObjectMapper()
                                    .createObjectNode()
                                    .put("branchId", branch)
                                    .put("projectId", "metrics")
                                    .put("gitRef", "refs/heads/dev/" + branch)
                                    .put("mode", "PREVIEW")
                                    .put("status", "ACTIVE"));
        }
        git = spy(new GitRepositoryClient(properties));
        workspaces =
                new GitWorkspaceService(
                        workspaceRecords,
                        changeRecords,
                        git,
                        new GitWorkspaceFiles(properties),
                        properties,
                        application,
                        new GitWorkspaceLocks(),
                        registry,
                        sessions);
        changes =
                new GitChangeService(
                        workspaces,
                        changeRecords,
                        new GitSnapshotStore(properties),
                        git,
                        application,
                        (session, snapshot) -> null,
                        (execution, candidate) -> {
                            candidate.setConversationMessageId("message");
                            changeRecords.register(candidate);
                        });
        queue = new ArrayDeque<>();
        submissions =
                new GitSubmissionService(
                        workspaces, changes, changeRecords, git, queue::add, Clock.systemUTC());
        sync = new GitWorkspaceSyncService(workspaces, git);
    }

    GitChangeView edit(String workspace, String session, String file, String value) {
        var current =
                workspaces.apply(
                        workspace,
                        List.of(
                                FileOperation.update(
                                        file, workspaces.read(workspace, file).sha256(), value)),
                        workspaces.get(workspace).draftRevision());
        return changes.prepareWorkspace(
                workspace,
                session,
                "execution",
                UUID.randomUUID().toString(),
                "修改定义",
                "update definition",
                current.draftRevision());
    }

    void push(String session, GitChangeView candidate) {
        submissions.confirm(session, candidate.changeId(), candidate.version());
        queue.remove().run();
    }

    @Test
    void sharedWorkspaceRejectsStaleEditAndRestartBeforeChangingFiles() {
        var w = workspaces.create("one", "a", base, "shared");
        workspaces.attach("two", w.workspaceId());
        var changed =
                workspaces.apply(
                        w.workspaceId(), List.of(FileOperation.create("new.yml", "new")), 0L);
        assertThrows(
                GitException.class,
                () ->
                        workspaces.apply(
                                w.workspaceId(),
                                List.of(FileOperation.create("stale.yml", "stale")),
                                0L));
        assertThrows(GitException.class, () -> workspaces.prepare(w.workspaceId(), true, 0L));
        assertEquals(changed.draftRevision(), workspaces.get(w.workspaceId()).draftRevision());
        assertEquals("new", workspaces.read(w.workspaceId(), "new.yml").content());
        assertFalse(workspaces.list(w.workspaceId(), ".").contains("stale.yml"));
    }

    @Test
    void explicitRecoveryRetainsBrokenMaterialsAndRestoresSameWorkspace() throws Exception {
        var w = workspaces.create("one", "a", base, "recover");
        var record = workspaceRecords.findByWorkspaceId(w.workspaceId());
        var original = workspaces.path(record);
        Files.writeString(original.resolve("metrics.yml"), "interrupted edit");
        record.setStatus("MUTATING");
        assertTrue(workspaceRecords.replace(record));
        var recovered = workspaces.prepare(w.workspaceId(), true, w.draftRevision());
        assertEquals(w.workspaceId(), recovered.workspaceId());
        assertEquals("READY", recovered.status());
        assertEquals(w.draftRevision() + 1, recovered.draftRevision());
        assertEquals("interrupted edit", Files.readString(original.resolve("metrics.yml")));
        assertEquals(
                "metric: original\n", workspaces.read(w.workspaceId(), "metrics.yml").content());
    }

    @Test
    void twoWorkspacesSameBranchRequireSyncAndInvalidateOldCandidate() throws Exception {
        var a = workspaces.create("one", "a", base, "one");
        var b = workspaces.create("two", "a", base, "two");
        var ca = edit(a.workspaceId(), "one", "metrics.yml", "metric: first\n");
        var cb = edit(b.workspaceId(), "two", "dimensions.yml", "dimension: second\n");
        push("one", ca);
        assertEquals(GitChangeStatus.PUSHED, changes.detail("one", ca.changeId()).status());
        var merged = sync.sync(b.workspaceId(), cb.draftRevision());
        assertEquals("MERGED", merged.status());
        assertEquals(cb.draftRevision() + 1, merged.draftRevision());
        assertEquals(GitChangeStatus.INVALIDATED, changes.detail("two", cb.changeId()).status());
        assertEquals("metric: first\n", workspaces.read(b.workspaceId(), "metrics.yml").content());
        assertEquals(
                "dimension: second\n",
                workspaces.read(b.workspaceId(), "dimensions.yml").content());
        var updated =
                changes.prepareWorkspace(
                        b.workspaceId(),
                        "two",
                        "execution",
                        "updated",
                        "合并后确认",
                        "update",
                        merged.draftRevision());
        push("two", updated);
        assertEquals(GitChangeStatus.PUSHED, changes.detail("two", updated.changeId()).status());
        assertEquals(base, run(seed, "ls-remote", "origin", "refs/heads/main").split("\\s+")[0]);
    }

    @Test
    void conflictCanBeReadResolvedAndPushedAgainstFixedRemote() {
        var a = workspaces.create("one", "a", base, "one");
        var b = workspaces.create("two", "a", base, "two");
        var ca = edit(a.workspaceId(), "one", "metrics.yml", "metric: first\n");
        var cb = edit(b.workspaceId(), "two", "metrics.yml", "metric: second\n");
        push("one", ca);
        var conflict = sync.sync(b.workspaceId(), cb.draftRevision());
        assertEquals("metric: original\n", conflict.conflicts().getFirst().baseContent());
        assertEquals("metric: second\n", conflict.conflicts().getFirst().localContent());
        assertEquals("metric: first\n", conflict.conflicts().getFirst().remoteContent());
        assertThrows(
                GitException.class,
                () ->
                        sync.resolve(
                                b.workspaceId(),
                                cb.draftRevision(),
                                base,
                                Map.of("metrics.yml", "metric: combined\n")));
        var resolved =
                sync.resolve(
                        b.workspaceId(),
                        cb.draftRevision(),
                        conflict.remoteCommitSha(),
                        Map.of("metrics.yml", "metric: combined\n"));
        assertEquals(conflict.remoteCommitSha(), resolved.baseCommitSha());
        assertEquals(cb.draftRevision() + 1, resolved.draftRevision());
        assertEquals(GitChangeStatus.INVALIDATED, changes.detail("two", cb.changeId()).status());
        assertThrows(
                GitException.class,
                () ->
                        sync.resolve(
                                b.workspaceId(),
                                cb.draftRevision(),
                                conflict.remoteCommitSha(),
                                Map.of("metrics.yml", "metric: stale\n")));
        var candidate =
                changes.prepareWorkspace(
                        b.workspaceId(),
                        "two",
                        "execution",
                        "resolved",
                        "人工合并后确认",
                        "resolve",
                        resolved.draftRevision());
        push("two", candidate);
        assertEquals(GitChangeStatus.PUSHED, changes.detail("two", candidate.changeId()).status());
        assertEquals(
                "metric: combined\n", workspaces.read(b.workspaceId(), "metrics.yml").content());
    }

    @Test
    void conflictingSyncPreservesOriginalDraftAndRevision() {
        var a = workspaces.create("one", "a", base, "one");
        var b = workspaces.create("two", "a", base, "two");
        var ca = edit(a.workspaceId(), "one", "metrics.yml", "metric: first\n");
        var cb = edit(b.workspaceId(), "two", "metrics.yml", "metric: second\n");
        push("one", ca);
        var result = sync.sync(b.workspaceId(), cb.draftRevision());
        assertEquals("CONFLICT", result.status());
        assertEquals(List.of("metrics.yml"), result.conflictPaths());
        assertEquals(cb.draftRevision(), result.draftRevision());
        assertEquals("metric: second\n", workspaces.read(b.workspaceId(), "metrics.yml").content());
    }

    @Test
    void candidateDoesNotFollowLaterWorkspaceSelectionAndDeletedBranchIsNotRecreated()
            throws Exception {
        var a = workspaces.create("one", "a", base, "one");
        var ca = edit(a.workspaceId(), "one", "metrics.yml", "metric: first\n");
        workspaces.create("one", "b", base, "other");
        run(seed, "push", "origin", "--delete", "dev/a");
        push("one", ca);
        assertNotEquals(GitChangeStatus.PUSHED, changes.detail("one", ca.changeId()).status());
        assertEquals("", run(seed, "ls-remote", "origin", "refs/heads/dev/a"));
        assertEquals(base, run(seed, "ls-remote", "origin", "refs/heads/dev/b").split("\\s+")[0]);
    }

    @Test
    void pushTimeoutReconcilesOriginalCommitInOriginalBranch() throws Exception {
        var a = workspaces.create("one", "a", base, "one");
        var ca = edit(a.workspaceId(), "one", "metrics.yml", "metric: first\n");
        workspaces.create("one", "b", base, "other");
        doAnswer(
                        invocation -> {
                            invocation.callRealMethod();
                            throw new GitException("GIT_TIMEOUT", "response lost");
                        })
                .when(git)
                .pushCommit(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
        push("one", ca);
        var pushed = changes.detail("one", ca.changeId());
        assertEquals(GitChangeStatus.PUSHED, pushed.status());
        assertEquals(
                pushed.commitId(),
                run(seed, "ls-remote", "origin", "refs/heads/dev/a").split("\\s+")[0]);
        assertEquals(base, run(seed, "ls-remote", "origin", "refs/heads/dev/b").split("\\s+")[0]);
        assertEquals(pushed.commitId(), submissions.reconcile("one", ca.changeId()).commitId());
        verify(git, times(1))
                .pushCommit(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("refs/heads/dev/a"),
                        org.mockito.ArgumentMatchers.eq(base),
                        org.mockito.ArgumentMatchers.eq(pushed.commitId()));
    }

    @Test
    void syncWithoutLocalEditsAdoptsRemoteWithoutChangingOriginalClone() {
        var a = workspaces.create("one", "a", base, "one");
        var b = workspaces.create("two", "a", base, "two");
        var before = workspaces.path(workspaceRecords.findByWorkspaceId(b.workspaceId()));
        push("one", edit(a.workspaceId(), "one", "metrics.yml", "metric: first\n"));
        assertEquals("MERGED", sync.sync(b.workspaceId(), 0).status());
        assertEquals("metric: first\n", workspaces.read(b.workspaceId(), "metrics.yml").content());
        assertEquals(base, git.head(before));
    }

    static String run(Path directory, String... args) throws Exception {
        return GitLifecycleTest.run(directory, args).strip();
    }
}
