package com.datagent.agentv2.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.persistence.service.memory.MemoryEntryService;
import com.datagent.agentv2.persistence.service.memory.MemoryOperationService;
import com.datagent.agentv2.persistence.service.memory.MemoryReviewJobService;
import com.datagent.agentv2.persistence.service.memory.MemoryReviewLeaseService;
import com.datagent.agentv2.persistence.service.memory.MemoryStateService;
import com.datagent.agentv2.persistence.service.memory.MemorySuppressionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = MemoryIntegrationTestConfiguration.class)
class MemoryServiceTest {
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET TIME ZONE 'UTC'");
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Autowired MemoryService memory;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemoryEvidencePort evidencePort;
    @Autowired MemoryStateService states;
    @Autowired MemoryOperationService operations;
    @Autowired MemoryEntryService entries;
    @Autowired MemoryReviewJobService jobs;
    @Autowired MemoryReviewLeaseService leases;
    @Autowired MemorySuppressionService suppressions;
    @Autowired MemoryContentPolicy contentPolicy;

    @Autowired SimpleMeterRegistry metricRegistry;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;

    @Test
    void metricsCountOnlyCommittedNewOperationsAndPublishInventory() {
        metricRegistry.clear();
        transactions.executeWithoutResult(
                tx -> {
                    memory.execute(ui("rollback"), add("中文"));
                    assertThat(metricRegistry.find("memory.operations").counter()).isNull();
                    tx.setRollbackOnly();
                });
        assertThat(metricRegistry.find("memory.operations").counter()).isNull();
        memory.execute(ui("committed"), add("中文"));
        memory.execute(ui("committed"), add("中文"));
        assertThat(
                        metricRegistry
                                .get("memory.operations")
                                .tag("status", "CREATED")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        metricRegistry
                                .get("memory.quota.used")
                                .tag("kind", "person_profile")
                                .gauge()
                                .value())
                .isEqualTo(2);
        memory.execute(ui("rejected"), add("x".repeat(801)));
        assertThat(
                        metricRegistry
                                .get("memory.operations")
                                .tag("status", "QUOTA_EXCEEDED")
                                .counter()
                                .count())
                .isEqualTo(1);
    }

    @BeforeEach
    void seed() {
        jdbc.execute("truncate tenant cascade");
        jdbc.update("insert into tenant(tenant_id,name) values ('tenant_local','Local')");
        jdbc.update(
                "insert into user_account(user_id,tenant_id,username,password_hash) values ('user_admin','tenant_local','admin','!')");
        reset(evidencePort);
    }

    @Test
    void sameRequestCannotCreateTwice() {
        assertThat(AopUtils.isAopProxy(memory)).isTrue();
        var first = memory.execute(ui("req-1"), add("用户主要使用中文"));
        assertThat(first.status()).isEqualTo(MemoryStatus.CREATED);
        assertThat(memory.execute(ui("req-1"), add("用户主要使用中文"))).isEqualTo(first);
        assertThat(memory.snapshot().entries()).hasSize(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from personal_memory_evidence", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void normalizedDuplicateSucceedsAtCapacityAndKeepsOriginalText() {
        String original = "A".repeat(800);
        memory.execute(ui("full"), add(original));
        var duplicate = memory.execute(ui("duplicate"), add(" \t" + "a".repeat(800) + "！"));
        assertThat(duplicate.status()).isEqualTo(MemoryStatus.ALREADY_EXISTS);
        assertThat(duplicate.quotas().get(MemoryKind.PERSON_PROFILE).used()).isEqualTo(800);
        assertThat(memory.snapshot().entries().getFirst().content()).isEqualTo(original);
    }

    @Test
    void unsafeWhitespaceCannotBypassValidationThroughDuplicate() {
        memory.execute(ui("first"), add("中文"));
        var result = memory.execute(ui("unsafe"), add("\u000B中文"));
        assertThat(result.status()).isEqualTo(MemoryStatus.UNSAFE_CONTENT);
        assertThat(memory.snapshot().state().revision()).isEqualTo(1);
        assertThat(operations.find("unsafe").outcome()).isEqualTo(result);
    }

    @Test
    void replacementCollisionAndOldVersionDoNotChangeContent() {
        var first = memory.execute(ui("one"), add("中文"));
        memory.execute(ui("two"), add("English"));
        var collision = memory.execute(ui("collision"), replace(first, "ENGLISH!"));
        assertThat(collision.status()).isEqualTo(MemoryStatus.DUPLICATE_CONFLICT);
        var changed = memory.execute(ui("replace"), replace(first, "日本語"));
        assertThat(changed.status()).isEqualTo(MemoryStatus.UPDATED);
        assertThat(changed.version()).isEqualTo(2);
        assertThat(changed.revision()).isEqualTo(3);
        assertThat(memory.execute(ui("stale"), replace(first, "其他")).status())
                .isEqualTo(MemoryStatus.CONFLICT);
        assertThat(entries.find(first.memoryId()).content()).isEqualTo("日本語");
    }

    @Test
    void quotaUsesUtf16AndFinalReplacementInventory() {
        var first = memory.execute(ui("one"), add("a".repeat(400)));
        memory.execute(ui("two"), add("b".repeat(400)));
        var rejected = memory.execute(ui("too-long"), replace(first, "😀".repeat(201)));
        assertThat(rejected.status()).isEqualTo(MemoryStatus.QUOTA_EXCEEDED);
        assertThat(rejected.quotas().get(MemoryKind.PERSON_PROFILE).used()).isEqualTo(800);
        assertThat(memory.execute(ui("fits"), replace(first, "😀".repeat(200))).status())
                .isEqualTo(MemoryStatus.UPDATED);
        assertThat(memory.snapshot().quotas().get(MemoryKind.PERSON_PROFILE).used()).isEqualTo(800);
    }

    @Test
    void requestConflictDoesNotOverwriteOriginalResult() {
        var first = memory.execute(ui("req"), add("中文"));
        assertThat(memory.execute(ui("req"), add("英文")).status()).isEqualTo(MemoryStatus.CONFLICT);
        assertThat(
                        memory.execute(
                                        ui("req"),
                                        new MemoryCommand.Add(
                                                MemoryKind.PERSON_PROFILE,
                                                "中文",
                                                MemoryWriteReason.USER_REQUESTED,
                                                List.of(),
                                                true))
                                .status())
                .isEqualTo(MemoryStatus.CONFLICT);
        assertThat(operations.find("req").outcome()).isEqualTo(first);
    }

    @Test
    void replayKeepsHistoricalQuotaAndRevisionButReportsCurrentExistence() {
        var first = memory.execute(ui("old"), add("中文"));
        memory.execute(ui("new"), add("第二条"));
        entries.deletePhysical(first.memoryId(), first.version());
        var replay = memory.execute(ui("old"), add("中文"));
        assertThat(replay.status()).isEqualTo(MemoryStatus.CREATED);
        assertThat(replay.created()).isTrue();
        assertThat(replay.targetExists()).isFalse();
        assertThat(replay.revision()).isEqualTo(1);
        assertThat(replay.quotas().get(MemoryKind.PERSON_PROFILE).used()).isEqualTo(2);
        assertThat(memory.snapshot().entries()).hasSize(1);
    }

    @Test
    void deterministicFailureIsCommittedAndReplayed() {
        var command = add("a".repeat(801));
        var rejected = memory.execute(ui("reject"), command);
        assertThat(rejected.status()).isEqualTo(MemoryStatus.QUOTA_EXCEEDED);
        assertThat(operations.find("reject").outcome()).isEqualTo(rejected);
        assertThat(memory.execute(ui("reject"), command)).isEqualTo(rejected);
        assertThat(memory.snapshot().state().revision()).isZero();
    }

    @Test
    void staleModelRevisionCannotBeBypassedUsingNewRequestId() {
        memory.execute(ui("human"), add("中文"));
        stubEvidence(userEvidence("msg", "execution", 1));
        var command = inferred("偏好简洁", "msg");
        assertThat(memory.execute(main("old", 0), command).status())
                .isEqualTo(MemoryStatus.CONFLICT);
        assertThat(memory.execute(main("new-id", 0), command).status())
                .isEqualTo(MemoryStatus.CONFLICT);
        assertThat(memory.snapshot().entries()).hasSize(1);
    }

    @Test
    void sameModelRequestReplaysAfterItsOwnCommitAdvancesRevision() {
        stubEvidence(userEvidence("msg", "execution", 1));
        var command = inferred("中文", "msg");
        var first = memory.execute(main("stable", 0), command);
        assertThat(first.status()).isEqualTo(MemoryStatus.CREATED);
        assertThat(memory.execute(main("stable", 1), command)).isEqualTo(first);
    }

    @Test
    void payloadHashIncludesReasonEvidenceTargetAndExpectedVersion() {
        stubEvidence(userEvidence("msg", "execution", 1));
        var original = memory.execute(main("add", 0), inferred("中文", "msg"));
        var explicit =
                new MemoryCommand.Add(
                        MemoryKind.PERSON_PROFILE,
                        "中文",
                        MemoryWriteReason.USER_REQUESTED,
                        List.of("msg"),
                        false);
        assertThat(memory.execute(main("add", 1), explicit).status())
                .isEqualTo(MemoryStatus.CONFLICT);
        assertThat(memory.execute(main("add", 1), inferred("中文", "different")).status())
                .isEqualTo(MemoryStatus.CONFLICT);
        var replace = replace(original, "英文");
        var updated = memory.execute(ui("replace"), replace);
        assertThat(updated.status()).isEqualTo(MemoryStatus.UPDATED);
        assertThat(memory.execute(ui("replace"), replace(updated, "英文")).status())
                .isEqualTo(MemoryStatus.CONFLICT);
        assertThat(
                        memory.execute(
                                        ui("replace"),
                                        new MemoryCommand.Replace(
                                                "other",
                                                1,
                                                "英文",
                                                MemoryWriteReason.USER_REQUESTED,
                                                List.of()))
                                .status())
                .isEqualTo(MemoryStatus.CONFLICT);
    }

    @Test
    void suppressionAppliesAcrossKindsBeforeQuotaAndNeverClearsOnRejectedRestore() {
        memory.initialize();
        String hash = contentPolicy.hash("中文");
        suppressions.insert(
                new MemorySuppression(1, hash, 1, states.currentBusinessTime(), "deleted"));
        for (var kind : MemoryKind.values()) {
            var command =
                    new MemoryCommand.Add(
                            kind, " \t中文！", MemoryWriteReason.USER_REQUESTED, List.of(), false);
            assertThat(memory.execute(ui("suppressed-" + kind), command).status())
                    .isEqualTo(MemoryStatus.SUPPRESSED);
        }
        assertThat(memory.snapshot().entries()).isEmpty();
        assertThat(suppressions.find(hash)).isNotNull();
    }

    @Test
    void missingEvidenceOrForeignAuthorCannotAuthorizeModelWrite() {
        assertThat(
                        memory.execute(
                                        main("empty", 0),
                                        new MemoryCommand.Add(
                                                MemoryKind.PERSON_PROFILE,
                                                "中文",
                                                MemoryWriteReason.USER_REQUESTED,
                                                List.of(),
                                                false))
                                .status())
                .isEqualTo(MemoryStatus.INVALID_REQUEST);
        stubEvidence(
                new MemoryEvidence(
                        "msg",
                        MemoryEvidence.Kind.USER_MESSAGE,
                        "conversation",
                        "execution",
                        1,
                        "other-user",
                        1,
                        "消息",
                        LocalDateTime.now()));
        assertThat(memory.execute(main("author", 0), inferred("中文", "msg")).status())
                .isEqualTo(MemoryStatus.INVALID_REQUEST);
        stubEvidence(
                new MemoryEvidence(
                        "msg",
                        MemoryEvidence.Kind.USER_MESSAGE,
                        "old-conversation",
                        "execution",
                        1,
                        "user_admin",
                        1,
                        "消息",
                        LocalDateTime.now()));
        assertThat(memory.execute(main("conversation", 0), inferred("中文", "msg")).status())
                .isEqualTo(MemoryStatus.INVALID_REQUEST);
    }

    @Test
    void dependencyFailureDoesNotPersistARejectionThatWouldPoisonRetry() {
        when(evidencePort.resolve(any(), anyList()))
                .thenThrow(new MemoryException(MemoryStatus.UNAVAILABLE, "证据暂不可用"));
        assertThatThrownBy(() -> memory.execute(main("retryable", 0), inferred("中文", "msg")))
                .isInstanceOf(MemoryException.class);
        assertThat(operations.find("retryable")).isNull();
        stubEvidence(userEvidence("msg", "execution", 1));
        assertThat(memory.execute(main("retryable", 0), inferred("中文", "msg")).status())
                .isEqualTo(MemoryStatus.CREATED);
    }

    @Test
    void evidenceMustBeRealCurrentUserMessageInSameTransaction() {
        when(evidencePort.resolve(any(), anyList()))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isTrue();
                            return List.of(userEvidence("msg", "execution", 1));
                        });
        assertThat(memory.execute(main("valid", 0), inferred("中文", "msg")).status())
                .isEqualTo(MemoryStatus.CREATED);
        stubEvidence(userEvidence("msg", "old-execution", 1));
        assertThat(memory.execute(main("old-msg", 1), inferred("英文", "msg")).status())
                .isEqualTo(MemoryStatus.INVALID_REQUEST);
        stubEvidence(
                new MemoryEvidence(
                        "msg",
                        MemoryEvidence.Kind.ASSISTANT_CONTEXT,
                        "conversation",
                        "execution",
                        1,
                        "user_admin",
                        1,
                        "消息",
                        LocalDateTime.now()));
        assertThat(memory.execute(main("assistant", 1), inferred("英文", "msg")).status())
                .isEqualTo(MemoryStatus.INVALID_REQUEST);
        stubEvidence(userEvidence("different-id", "execution", 1));
        assertThat(memory.execute(main("forged", 1), inferred("英文", "msg")).status())
                .isEqualTo(MemoryStatus.INVALID_REQUEST);
    }

    @Test
    void automaticDuplicateDoesNotRefreshAndExplicitDuplicateProtectsOriginalSource() {
        stubEvidence(userEvidence("msg", "execution", 1));
        var first = memory.execute(main("auto", 0), inferred("中文", "msg"));
        var original = entries.find(first.memoryId());
        var duplicate = memory.execute(main("duplicate", 1), inferred("中文！", "msg"));
        assertThat(duplicate.status()).isEqualTo(MemoryStatus.ALREADY_EXISTS);
        assertThat(duplicate.revision()).isEqualTo(1);
        assertThat(entries.find(first.memoryId())).isEqualTo(original);
        var protectedResult = memory.execute(ui("protect"), add("中文"));
        assertThat(protectedResult.status()).isEqualTo(MemoryStatus.ALREADY_EXISTS);
        assertThat(protectedResult.revision()).isEqualTo(2);
        assertThat(entries.find(first.memoryId()).userProtected()).isTrue();
        assertThat(entries.find(first.memoryId()).source()).isEqualTo(MemorySource.LLM_ACTIVE);
        var automatic =
                new MemoryCommand.Replace(
                        first.memoryId(),
                        2,
                        "英文",
                        MemoryWriteReason.AGENT_INFERRED,
                        List.of("msg"));
        assertThat(memory.execute(main("protected", 2), automatic).status())
                .isEqualTo(MemoryStatus.PROTECTED);
        var explicit =
                new MemoryCommand.Replace(
                        first.memoryId(),
                        2,
                        "英文",
                        MemoryWriteReason.USER_REQUESTED,
                        List.of("msg"));
        assertThat(memory.execute(main("explicit", 2), explicit).status())
                .isEqualTo(MemoryStatus.UPDATED);
    }

    @Test
    void switchesArePersistentAndSeparateExplicitFromAutomaticWrites() {
        memory.initialize();
        jdbc.update(
                "update personal_memory_state set auto_learning_enabled=false,learning_epoch=2,learning_enabled_at=null");
        memory.initialize();
        assertThat(memory.snapshot().state().autoLearningEnabled()).isFalse();
        stubEvidence(userEvidence("msg", "execution", 1));
        assertThat(memory.execute(main("auto", 0), inferred("中文", "msg")).status())
                .isEqualTo(MemoryStatus.LEARNING_DISABLED);
        assertThat(memory.execute(ui("explicit"), add("中文")).status())
                .isEqualTo(MemoryStatus.CREATED);
        jdbc.update("update personal_memory_state set explicit_write_enabled=false");
        assertThat(memory.execute(ui("disabled"), add("英文")).successful()).isFalse();
    }

    @Test
    void initializationAndBusinessTimeAreShanghaiEvenWithUtcSession() {
        assertThat(jdbc.queryForObject("show timezone", String.class)).isEqualTo("UTC");
        var before =
                jdbc.queryForObject(
                        "select clock_timestamp() at time zone 'Asia/Shanghai'",
                        LocalDateTime.class);
        memory.initialize();
        var actual = memory.snapshot().state().learningEnabledAt();
        var business = states.currentBusinessTime();
        var after =
                jdbc.queryForObject(
                        "select clock_timestamp() at time zone 'Asia/Shanghai'",
                        LocalDateTime.class);
        assertThat(actual).isBetween(before, after);
        assertThat(business).isBetween(before, after);
        memory.initialize();
        assertThat(memory.snapshot().state().learningEnabledAt()).isEqualTo(actual);
    }

    @Test
    void backgroundMustValidateBothClaimsAndFrozenEvidenceRange() {
        memory.initialize();
        var claim = claimFixture();
        stubEvidence(userEvidence("msg", "completed-execution", 11));
        var ctx = background("bg", 0, claim);
        assertThat(memory.execute(ctx, inferred("中文", "msg")).status())
                .isEqualTo(MemoryStatus.CREATED);
        assertThat(entries.listCurrent().getFirst().source())
                .isEqualTo(MemorySource.BACKGROUND_REVIEW);
        stubEvidence(userEvidence("msg", "completed-execution", 10));
        assertThat(memory.execute(background("boundary", 1, claim), inferred("英文", "msg")).status())
                .isEqualTo(MemoryStatus.INVALID_REQUEST);
        stubEvidence(userEvidence("msg", "completed-execution", 20));
        jdbc.update("update personal_memory_review_lease set fencing_token=8");
        assertThat(memory.execute(background("fenced", 1, claim), inferred("英文", "msg")).status())
                .isEqualTo(MemoryStatus.LEASE_LOST);
        assertThat(memory.snapshot().entries()).hasSize(1);
    }

    @Test
    void expiredTaskOrOldEpochCannotWriteEvenWithLiveUserLease() {
        memory.initialize();
        var claim = claimFixture();
        stubEvidence(userEvidence("msg", "execution", 11));
        jdbc.update(
                "update personal_memory_review_job set lease_until=clock_timestamp()-interval '1 second'");
        assertThat(memory.execute(background("expired", 0, claim), inferred("中文", "msg")).status())
                .isEqualTo(MemoryStatus.LEASE_LOST);
        jdbc.update("update personal_memory_state set learning_epoch=2");
        assertThat(memory.execute(background("epoch", 0, claim), inferred("中文", "msg")).status())
                .isEqualTo(MemoryStatus.LEASE_LOST);
    }

    @Test
    void leaseExpiringDuringEvidenceReadCannotCommit() {
        memory.initialize();
        var until = Instant.now().plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var claim = claimFixture(until);
        var resolved = new java.util.concurrent.atomic.AtomicBoolean();
        when(evidencePort.resolve(any(), anyList()))
                .thenAnswer(
                        invocation -> {
                            resolved.set(true);
                            long millis =
                                    java.time.Duration.between(Instant.now(), until.plusMillis(50))
                                            .toMillis();
                            if (millis > 0) Thread.sleep(millis);
                            return List.of(userEvidence("msg", "execution", 11));
                        });
        var result =
                memory.execute(background("expired-during-read", 0, claim), inferred("中文", "msg"));
        assertThat(resolved).isTrue();
        assertThat(result.status()).isEqualTo(MemoryStatus.LEASE_LOST);
        assertThat(memory.snapshot().entries()).isEmpty();
    }

    @Test
    void databaseFailureRollsBackEntryEvidenceRevisionAndOperation() {
        memory.initialize();
        jdbc.execute(
                "create function reject_memory_operation() returns trigger language plpgsql as 'begin raise exception ''test failure''; end'");
        jdbc.execute(
                "create trigger reject_operation before insert on personal_memory_operation for each row execute function reject_memory_operation()");
        try {
            assertThatThrownBy(() -> memory.execute(ui("rollback"), add("中文")))
                    .isInstanceOf(RuntimeException.class);
            assertThat(memory.snapshot().entries()).isEmpty();
            assertThat(memory.snapshot().state().revision()).isZero();
            assertThat(operations.find("rollback")).isNull();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from personal_memory_evidence", Integer.class))
                    .isZero();
        } finally {
            jdbc.execute("drop trigger reject_operation on personal_memory_operation");
            jdbc.execute("drop function reject_memory_operation()");
        }
    }

    private MemoryReviewClaim claimFixture() {
        var until =
                Instant.now().plusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return claimFixture(until);
    }

    private MemoryReviewClaim claimFixture(Instant until) {
        leases.insert(new MemoryReviewLease(7, "worker", until));
        jobs.insert(
                new MemoryReviewJob(
                        "job",
                        "conversation",
                        1,
                        10,
                        20,
                        20,
                        1,
                        1,
                        MemoryReviewJob.Status.RUNNING,
                        LocalDateTime.now(),
                        0,
                        null,
                        "worker",
                        11,
                        until));
        return new MemoryReviewClaim("job", "conversation", 1, 11, 7, "worker", 1, 10, 20, until);
    }

    private void stubEvidence(MemoryEvidence evidence) {
        doReturn(List.of(evidence)).when(evidencePort).resolve(any(), anyList());
    }

    static MemoryWriteContext ui(String id) {
        return new MemoryWriteContext(MemoryWriteContext.Caller.UI, id, null, null, null, null);
    }

    static MemoryWriteContext main(String id, long revision) {
        return new MemoryWriteContext(
                MemoryWriteContext.Caller.MAIN, id, "execution", "conversation", revision, null);
    }

    static MemoryWriteContext background(String id, long revision, MemoryReviewClaim claim) {
        return new MemoryWriteContext(
                MemoryWriteContext.Caller.BACKGROUND, id, null, "conversation", revision, claim);
    }

    static MemoryCommand.Add add(String content) {
        return new MemoryCommand.Add(
                MemoryKind.PERSON_PROFILE,
                content,
                MemoryWriteReason.USER_REQUESTED,
                List.of(),
                false);
    }

    static MemoryCommand.Add inferred(String content, String evidence) {
        return new MemoryCommand.Add(
                MemoryKind.PERSON_PROFILE,
                content,
                MemoryWriteReason.AGENT_INFERRED,
                List.of(evidence),
                false);
    }

    static MemoryCommand.Replace replace(MemoryOutcome target, String content) {
        return new MemoryCommand.Replace(
                target.memoryId(),
                target.version(),
                content,
                MemoryWriteReason.USER_REQUESTED,
                List.of());
    }

    static MemoryEvidence userEvidence(String id, String execution, long sequence) {
        return new MemoryEvidence(
                id,
                MemoryEvidence.Kind.USER_MESSAGE,
                "conversation",
                execution,
                sequence,
                "user_admin",
                1,
                "真实用户消息",
                LocalDateTime.now());
    }
}
