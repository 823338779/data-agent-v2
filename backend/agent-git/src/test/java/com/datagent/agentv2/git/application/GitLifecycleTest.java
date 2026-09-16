package com.datagent.agentv2.git.application;

import static org.junit.jupiter.api.Assertions.*;

import com.datagent.agentv2.git.config.*;
import com.datagent.agentv2.git.local.*;
import com.datagent.agentv2.git.model.*;
import com.datagent.agentv2.persistence.*;
import com.datagent.agentv2.persistence.service.git.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 使用真实 PostgreSQL 与裸仓库，捕获授权、持久化 CAS 和恢复次序错误。 */
@SpringBootTest(classes = GitLifecycleTest.Config.class)
class GitLifecycleTest {
    static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17-alpine");

    static {
        DB.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
    }

    @Configuration
    @EnableAutoConfiguration
    @Import({
        MybatisPlusConfiguration.class,
        AuditMetaObjectHandler.class,
        GitWorkspacePersistenceService.class,
        GitChangePersistenceService.class
    })
    static class Config {}

    @Autowired GitWorkspacePersistenceService workspaceRecords;
    @Autowired GitChangePersistenceService changeRecords;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @TempDir Path temp;
    GitRepositoryClient git;
    GitWorkspaceService workspaces;
    GitChangeService changes;
    GitSubmissionService submissions;
    ArrayDeque<Runnable> queue;
    Path remote;
    GitProperties properties;
    GitApplicationProperties application;

    @BeforeEach
    void setup() throws Exception {
        jdbc.execute("truncate table git_change, git_workspace restart identity cascade");
        remote = temp.resolve("remote.git");
        run(temp, "init", "--bare", "--initial-branch=main", remote.toString());
        Path seed = temp.resolve("seed");
        run(temp, "clone", remote.toString(), seed.toString());
        Files.writeString(seed.resolve("metrics.yml"), "old\n");
        run(seed, "add", ".");
        run(seed, "-c", "user.name=seed", "-c", "user.email=seed@test", "commit", "-m", "seed");
        run(seed, "push", "origin", "main");
        properties =
                new GitProperties(
                        "git",
                        remote.toString(),
                        temp.resolve("data"),
                        Duration.ofSeconds(15),
                        4 * 1024 * 1024,
                        512 * 1024,
                        20,
                        100,
                        5 * 1024 * 1024,
                        2 * 1024 * 1024);
        application = new GitApplicationProperties("metrics", "Agent", "agent@test");
        rebuild();
    }

    void rebuild() {
        git = new GitRepositoryClient(properties);
        var locks = new GitWorkspaceLocks();
        var snapshots = new GitSnapshotStore(properties);
        workspaces =
                new GitWorkspaceService(
                        workspaceRecords,
                        changeRecords,
                        git,
                        new GitWorkspaceFiles(properties),
                        properties,
                        application,
                        locks);
        changes =
                new GitChangeService(
                        workspaces,
                        changeRecords,
                        snapshots,
                        git,
                        application,
                        (execution, candidate) -> {
                            candidate.setConversationMessageId("message");
                            changeRecords.register(candidate);
                        });
        queue = new ArrayDeque<>();
        submissions =
                new GitSubmissionService(
                        workspaces, changes, changeRecords, git, queue::add, Clock.systemUTC());
    }

    GitChangeView candidate(String session) {
        var w = workspaces.prepare(session, false);
        workspaces.apply(
                session,
                List.of(
                        FileOperation.update(
                                "metrics.yml",
                                workspaces.read(session, "metrics.yml").sha256(),
                                session + "\n")));
        return changes.prepare(
                session,
                "execution",
                "request-" + session,
                "修改指标",
                "update " + session,
                w.draftRevision() + 1);
    }

    @Test
    void sessionsAreIsolatedAndCancellationPreservesDraft() {
        var first = candidate("one");
        var second = candidate("two");
        assertNotEquals(first.workspaceId(), second.workspaceId());
        submissions.cancel("one", first.changeId(), first.version());
        assertEquals("one\n", workspaces.read("one", "metrics.yml").content());
        assertEquals("two\n", workspaces.read("two", "metrics.yml").content());
        assertEquals(GitChangeStatus.CANCELLED, changes.detail("one", first.changeId()).status());
        assertThrows(
                GitException.class,
                () -> submissions.confirm("two", first.changeId(), first.version()));
    }

    @Test
    void editingInvalidatesOldCardAndCannotSubmitIt() {
        var c = candidate("one");
        workspaces.apply("one", List.of(FileOperation.create("new.yml", "new\n")));
        assertEquals(GitChangeStatus.INVALIDATED, changes.detail("one", c.changeId()).status());
        assertThrows(
                GitException.class, () -> submissions.confirm("one", c.changeId(), c.version()));
        assertTrue(queue.isEmpty());
    }

    @Test
    void confirmationQueuesOnceLocksDraftAndPushesFrozenTree() throws Exception {
        var c = candidate("one");
        String before = run(temp, "--git-dir=" + remote, "rev-parse", "main").trim();
        assertEquals(c.baseCommit(), before);
        submissions.confirm("one", c.changeId(), c.version());
        submissions.confirm("one", c.changeId(), c.version());
        assertEquals(1, queue.size());
        assertThrows(
                GitException.class,
                () -> workspaces.apply("one", List.of(FileOperation.create("later", "x"))));
        queue.remove().run();
        var result = changes.detail("one", c.changeId());
        assertEquals(GitChangeStatus.PUSHED, result.status());
        assertEquals("one\n", run(temp, "--git-dir=" + remote, "show", "main:metrics.yml"));
        assertEquals(result.commitId(), workspaces.prepare("one", false).baseCommit());
    }

    @Test
    void competingSessionsRequireNewConfirmation() {
        var a = candidate("one");
        var b = candidate("two");
        submissions.confirm("one", a.changeId(), a.version());
        submissions.confirm("two", b.changeId(), b.version());
        queue.remove().run();
        queue.remove().run();
        assertEquals(GitChangeStatus.PUSHED, changes.detail("one", a.changeId()).status());
        assertEquals(
                GitChangeStatus.RECONFIRM_REQUIRED, changes.detail("two", b.changeId()).status());
        assertEquals("two\n", workspaces.read("two", "metrics.yml").content());
    }

    @Test
    void restartDoesNotContinueConfirmedJobWithoutCommit() {
        var c = candidate("one");
        submissions.confirm("one", c.changeId(), c.version());
        rebuild();
        new GitRecoveryRunner(changeRecords, submissions).captureRecovery().run();
        assertEquals(GitChangeStatus.FAILED, changes.detail("one", c.changeId()).status());
        assertTrue(queue.isEmpty());
        var failed = changes.detail("one", c.changeId());
        submissions.retry("one", c.changeId(), failed.version());
        queue.remove().run();
        assertEquals(GitChangeStatus.PUSHED, changes.detail("one", c.changeId()).status());
    }

    @Test
    void lostPushResponseIsRecoveredWithoutPushingAgain() {
        var c = candidate("one");
        submissions.confirm("one", c.changeId(), c.version());
        queue.remove().run();
        var pushed = changes.detail("one", c.changeId());
        jdbc.update("update git_change set status='VERIFYING' where change_id=?", c.changeId());
        rebuild();
        new GitRecoveryRunner(changeRecords, submissions).captureRecovery().run();
        assertEquals(GitChangeStatus.PUSHED, changes.detail("one", c.changeId()).status());
        assertEquals(pushed.commitId(), changes.detail("one", c.changeId()).commitId());
        assertTrue(queue.isEmpty());
    }

    @Test
    void missingSnapshotRejectsConfirmationAfterRestart() throws Exception {
        var c = candidate("one");
        var record = changeRecords.find(c.changeId());
        Files.delete(properties.workspaceRoot().resolve(record.getSnapshotStorageKey()));
        rebuild();
        assertThrows(
                GitException.class, () -> submissions.confirm("one", c.changeId(), c.version()));
        assertEquals(GitChangeStatus.FAILED, changes.detail("one", c.changeId()).status());
        assertTrue(queue.isEmpty());
    }

    @Test
    void corruptedPersistedCommitCannotTurnUnpushedCandidateIntoSuccess() {
        var c = candidate("one");
        submissions.confirm("one", c.changeId(), c.version());
        jdbc.update(
                "update git_change set status='VERIFYING', commit_id=? where change_id=?",
                c.baseCommit(),
                c.changeId());
        rebuild();
        new GitRecoveryRunner(changeRecords, submissions).captureRecovery().run();
        assertEquals(GitChangeStatus.FAILED, changes.detail("one", c.changeId()).status());
        assertEquals("GIT_COMMIT_CORRUPT", changes.detail("one", c.changeId()).errorCode());
    }

    @Test
    void duplicatePrepareAndParallelConfirmProduceOneTask() throws Exception {
        var c = candidate("one");
        assertEquals(
                c.changeId(),
                changes.prepare("one", "execution", "request-one", "修改指标", "update one", 1)
                        .changeId());
        assertThrows(
                GitException.class,
                () -> changes.prepare("one", "execution", "request-one", "other", "update one", 1));
        try (var pool = Executors.newFixedThreadPool(6)) {
            var futures = new ArrayList<Future<GitChangeView>>();
            for (int i = 0; i < 6; i++)
                futures.add(
                        pool.submit(() -> submissions.confirm("one", c.changeId(), c.version())));
            for (var future : futures) assertEquals(c.changeId(), future.get().changeId());
        }
        assertEquals(1, queue.size());
        queue.remove().run();
        assertEquals(
                GitChangeStatus.PUSHED,
                submissions.confirm("one", c.changeId(), c.version()).status());
    }

    @Test
    void restartArchivesDraftAndOldCardStillHasItsFullDiff() throws Exception {
        var c = candidate("one");
        String oldKey = workspaceRecords.find("one", "metrics").getStorageKey();
        var fresh = workspaces.prepare("one", true);
        assertEquals("old\n", workspaces.read("one", "metrics.yml").content());
        assertEquals(
                "one\n",
                Files.readString(
                        properties.workspaceRoot().resolve(oldKey).resolve("metrics.yml")));
        assertEquals(GitChangeStatus.INVALIDATED, changes.detail("one", c.changeId()).status());
        assertTrue(changes.diff("one", c.changeId(), "0").contains("+one"));
        assertTrue(fresh.draftRevision() > c.draftRevision());
    }

    @Test
    void externalFileMutationInvalidatesApprovalInsteadOfSilentlyRebuilding() throws Exception {
        var c = candidate("one");
        var w = workspaceRecords.find("one", "metrics");
        Files.writeString(
                properties.workspaceRoot().resolve(w.getStorageKey()).resolve("metrics.yml"),
                "outside\n");
        rebuild();
        assertThrows(GitException.class, () -> workspaces.prepare("one", false));
        assertEquals("BROKEN", workspaceRecords.find("one", "metrics").getStatus());
        assertEquals(GitChangeStatus.INVALIDATED, changes.detail("one", c.changeId()).status());
    }

    @Test
    void recordedUnpushedCommitSurvivesGcAndRecoveryRetry() throws Exception {
        var c = candidate("one");
        var stopped = org.mockito.Mockito.spy(git);
        // 模拟 commit 登记完成、push 尚未执行时进程退出；Error 不被正常失败收敛路径吞掉。
        org.mockito.Mockito.doThrow(new AssertionError("process stopped before push"))
                .when(stopped)
                .pushMain(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
        submissions =
                new GitSubmissionService(
                        workspaces, changes, changeRecords, stopped, queue::add, Clock.systemUTC());
        submissions.confirm("one", c.changeId(), c.version());
        assertThrows(AssertionError.class, () -> queue.remove().run());
        var registered = changes.detail("one", c.changeId());
        assertEquals(GitChangeStatus.SUBMITTING, registered.status());
        assertNotNull(registered.commitId());
        var w = workspaceRecords.find("one", "metrics");
        run(properties.workspaceRoot().resolve(w.getStorageKey()), "gc", "--prune=now");

        rebuild();
        new GitRecoveryRunner(changeRecords, submissions).captureRecovery().run();
        var recovered = changes.detail("one", c.changeId());
        assertEquals(GitChangeStatus.FAILED, recovered.status());
        assertEquals("GIT_RETRY_REQUIRED", recovered.errorCode());
        assertTrue(queue.isEmpty());
        submissions.retry("one", c.changeId(), recovered.version());
        queue.remove().run();
        var pushed = changes.detail("one", c.changeId());
        assertEquals(GitChangeStatus.PUSHED, pushed.status());
        assertEquals(registered.commitId(), pushed.commitId());
    }

    @Test
    void recordedButUnpushedCommitIsReusedByUserRetry() {
        var c = candidate("one");
        submissions.confirm("one", c.changeId(), c.version());
        var record = changeRecords.find(c.changeId());
        var w = workspaceRecords.find("one", "metrics");
        String commit =
                git.createCommit(
                        properties.workspaceRoot().resolve(w.getStorageKey()),
                        record.getTreeId(),
                        record.getBaseCommit(),
                        record.getCommitMessage(),
                        record.getAuthorName(),
                        record.getAuthorEmail(),
                        record.getCommitTime());
        assertTrue(changeRecords.recordCommit(record, commit));
        rebuild();
        new GitRecoveryRunner(changeRecords, submissions).captureRecovery().run();
        var failed = changes.detail("one", c.changeId());
        assertEquals(GitChangeStatus.FAILED, failed.status());
        submissions.retry("one", c.changeId(), failed.version());
        submissions.retry("one", c.changeId(), failed.version());
        assertEquals(1, queue.size());
        queue.remove().run();
        assertEquals(GitChangeStatus.PUSHED, changes.detail("one", c.changeId()).status());
        assertEquals(commit, changes.detail("one", c.changeId()).commitId());
    }

    @Test
    void unreachableRemoteStaysVerifyingThenRecovers() throws Exception {
        var c = candidate("one");
        submissions.confirm("one", c.changeId(), c.version());
        queue.remove().run();
        jdbc.update("update git_change set status='VERIFYING' where change_id=?", c.changeId());
        Path offline = temp.resolve("offline.git");
        Files.move(remote, offline);
        rebuild();
        new GitRecoveryRunner(changeRecords, submissions).captureRecovery().run();
        assertEquals(GitChangeStatus.VERIFYING, changes.detail("one", c.changeId()).status());
        assertThrows(GitException.class, () -> workspaces.prepare("one", true));
        Files.move(offline, remote);
        submissions.reconcile("one", c.changeId());
        assertEquals(GitChangeStatus.PUSHED, changes.detail("one", c.changeId()).status());
    }

    @Test
    void publishingFailureRollsBackCandidateAndKeepsPreviousCardActive() {
        var c = candidate("one");
        var tx =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var failingPublisher =
                new GitChangeService(
                        workspaces,
                        changeRecords,
                        new GitSnapshotStore(properties),
                        git,
                        application,
                        (execution, record) ->
                                tx.executeWithoutResult(
                                        status -> {
                                            record.setConversationMessageId("message");
                                            changeRecords.register(record);
                                            throw new IllegalStateException(
                                                    "message storage failed");
                                        }));
        assertThrows(
                IllegalStateException.class,
                () ->
                        failingPublisher.prepare(
                                "one", "execution", "second-request", "第二张卡片", "second", 1));
        assertEquals(
                GitChangeStatus.PENDING_CONFIRMATION, changes.detail("one", c.changeId()).status());
        assertEquals(c.changeId(), workspaceRecords.find("one", "metrics").getActiveChangeId());
        assertEquals(1, jdbc.queryForObject("select count(*) from git_change", Integer.class));
    }

    @Test
    void successfulPushWithLostResponseIsVerifiedFromRealRemote() {
        var c = candidate("one");
        var uncertain = org.mockito.Mockito.spy(git);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            invocation.callRealMethod();
                            throw new GitException("GIT_TIMEOUT", "response lost");
                        })
                .when(uncertain)
                .pushMain(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
        submissions =
                new GitSubmissionService(
                        workspaces,
                        changes,
                        changeRecords,
                        uncertain,
                        queue::add,
                        Clock.systemUTC());
        submissions.confirm("one", c.changeId(), c.version());
        queue.remove().run();
        var result = changes.detail("one", c.changeId());
        assertEquals(GitChangeStatus.PUSHED, result.status());
        assertEquals(result.commitId(), workspaces.prepare("one", false).baseCommit());
    }

    @Test
    void candidateMetadataTamperingRejectsConfirmation() {
        var c = candidate("one");
        jdbc.update(
                "update git_change set commit_message='unreviewed change' where change_id=?",
                c.changeId());
        assertThrows(
                GitException.class, () -> submissions.confirm("one", c.changeId(), c.version()));
        assertEquals(GitChangeStatus.FAILED, changes.detail("one", c.changeId()).status());
        assertTrue(queue.isEmpty());
    }

    @Test
    void recoveredPushDoesNotBlessExternallyModifiedDraft() throws Exception {
        var c = candidate("one");
        submissions.confirm("one", c.changeId(), c.version());
        queue.remove().run();
        jdbc.update("update git_change set status='VERIFYING' where change_id=?", c.changeId());
        var w = workspaceRecords.find("one", "metrics");
        Files.writeString(
                properties.workspaceRoot().resolve(w.getStorageKey()).resolve("metrics.yml"),
                "external\n");
        rebuild();
        new GitRecoveryRunner(changeRecords, submissions).captureRecovery().run();
        assertEquals(GitChangeStatus.PUSHED, changes.detail("one", c.changeId()).status());
        assertEquals("BROKEN", workspaceRecords.find("one", "metrics").getStatus());
    }

    static String run(Path directory, String... args) throws Exception {
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(args));
        var process =
                new ProcessBuilder(command)
                        .directory(directory.toFile())
                        .redirectErrorStream(true)
                        .start();
        String output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }
}
