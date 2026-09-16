package com.datagent.agentv2.persistence.service.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.datagent.agentv2.memory.MemoryEntry;
import com.datagent.agentv2.memory.MemoryKind;
import com.datagent.agentv2.memory.MemoryOperation;
import com.datagent.agentv2.memory.MemoryOutcome;
import com.datagent.agentv2.memory.MemoryQuota;
import com.datagent.agentv2.memory.MemoryReviewClaim;
import com.datagent.agentv2.memory.MemoryReviewJob;
import com.datagent.agentv2.memory.MemoryReviewLease;
import com.datagent.agentv2.memory.MemorySource;
import com.datagent.agentv2.memory.MemoryStatus;
import com.datagent.agentv2.memory.MemorySuppression;
import com.datagent.agentv2.persistence.AgentPersistenceTestApplication;
import com.datagent.agentv2.persistence.entity.memory.MemoryRow;
import com.datagent.agentv2.persistence.mapping.MemoryPersistenceMapperImpl;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = AgentPersistenceTestApplication.class)
@Import({
    MemoryEntryService.class,
    MemoryStateService.class,
    MemoryReviewJobService.class,
    MemoryReviewLeaseService.class,
    MemoryOperationService.class,
    MemorySuppressionService.class,
    MemoryPersistenceMapperImpl.class
})
class MemoryPersistenceTest {
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
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MemoryEntryService entries;
    @Autowired private MemoryStateService states;
    @Autowired private MemoryReviewJobService jobs;
    @Autowired private MemoryReviewLeaseService leases;
    @Autowired private MemoryOperationService operations;
    @Autowired private MemorySuppressionService suppressions;
    @Autowired private TransactionTemplate transactions;

    @BeforeEach
    void seed() {
        jdbc.execute("truncate tenant cascade");
        jdbc.update("insert into tenant(tenant_id,name) values ('tenant_local','Local')");
        jdbc.update(
                "insert into user_account(user_id,tenant_id,username,password_hash) values ('user_admin','tenant_local','admin','!')");
    }

    @Test
    void memoryDeletionMustNotLeaveSoftDeletedContent() {
        assertThat(MemoryRow.class.getSuperclass()).isEqualTo(Object.class);
        assertThat(
                        Arrays.stream(MemoryRow.class.getDeclaredFields())
                                .anyMatch(f -> f.isAnnotationPresent(TableLogic.class)))
                .isFalse();
        entries.insert(entry("m1", "偏好简洁回答", 1));
        assertThat(entries.deletePhysical("m1", 1)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from personal_memory", Integer.class))
                .isZero();
    }

    @Test
    void entryCrudUsesBusinessVersionCasAndFixedIdentity() {
        var inserted = entries.insert(entry("m1", "偏好简洁回答", 1));
        assertThat(inserted.createdAt()).isNotNull();
        assertThat(entries.find("m1").content()).isEqualTo("偏好简洁回答");
        assertThat(entries.listCurrent()).extracting(MemoryEntry::memoryId).containsExactly("m1");
        assertThat(
                        jdbc.queryForObject(
                                "select tenant_id || ':' || user_id from personal_memory where memory_id='m1'",
                                String.class))
                .isEqualTo("tenant_local:user_admin");

        var replacement = entry("m1", "希望回答简短", 2);
        assertThat(entries.replace(replacement, 0)).isFalse();
        assertThat(entries.replace(replacement, 1)).isTrue();
        assertThat(entries.find("m1").content()).isEqualTo("希望回答简短");
    }

    @Test
    void entryReplacementCanClearNullableConfirmationFields() {
        entries.insert(entry("m1", "偏好简洁回答", 1));
        var previous = entries.find("m1");
        var replacement =
                new MemoryEntry(
                        previous.memoryId(),
                        previous.kind(),
                        "希望回答简短",
                        "hash-2",
                        previous.normalizationVersion(),
                        7,
                        previous.source(),
                        previous.userProtected(),
                        2,
                        previous.createdAt(),
                        previous.updatedAt(),
                        null,
                        null);

        assertThat(entries.replace(replacement, 1)).isTrue();
        assertThat(entries.find("m1").lastConfirmedAt()).isNull();
        assertThat(entries.find("m1").promptVersion()).isNull();
    }

    @Test
    void stateInitializationIsIdempotentAndReturnsPersistedSettings() {
        var first = states.lockCurrent();
        var second = states.lockCurrent();
        assertThat(first).isEqualTo(second);
        assertThat(first.revision()).isZero();
        assertThat(first.settingsVersion()).isZero();
        assertThat(first.readEnabled()).isTrue();
        assertThat(first.explicitWriteEnabled()).isTrue();
        assertThat(first.autoLearningEnabled()).isTrue();
        assertThat(first.learningEpoch()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from personal_memory_state", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void reviewClaimUsesDatabaseLeaseAndCasCanReleaseNullableOwnership() {
        var now = LocalDateTime.of(2026, 9, 17, 9, 0);
        var leaseUntil = Instant.now().plusSeconds(120);
        leases.insert(new MemoryReviewLease(7, "worker-1", leaseUntil));
        jobs.insert(
                new MemoryReviewJob(
                        "job-1",
                        "conversation-1",
                        3,
                        10,
                        20,
                        20,
                        4,
                        4,
                        MemoryReviewJob.Status.RUNNING,
                        now,
                        0,
                        null,
                        "worker-1",
                        11,
                        leaseUntil));
        var claim =
                new MemoryReviewClaim(
                        "job-1", "conversation-1", 3, 11, 7, "worker-1", 4, 10, 20, leaseUntil);
        assertThat(jobs.validateClaim(claim)).isTrue();
        assertThat(leases.validateClaim(claim)).isTrue();

        var releasedJob =
                new MemoryReviewJob(
                        "job-1",
                        "conversation-1",
                        3,
                        20,
                        20,
                        20,
                        4,
                        4,
                        MemoryReviewJob.Status.COMPLETED,
                        now,
                        0,
                        null,
                        null,
                        11,
                        null);
        assertThat(jobs.compareAndSet(releasedJob, 11)).isTrue();
        assertThat(leases.compareAndSet(new MemoryReviewLease(7, null, null), 7)).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "select owner is null and lease_until is null from personal_memory_review_job where job_id='job-1'",
                                Boolean.class))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "select owner is null and lease_until is null from personal_memory_review_lease",
                                Boolean.class))
                .isTrue();
        assertThat(jobs.validateClaim(claim)).isFalse();
    }

    @Test
    void renewedLeasesKeepOriginalClaimValidButChangedFencingDoesNot() {
        var fixture = insertClaimFixture(Instant.now().plusSeconds(30));
        var renewedUntil = fixture.leaseUntil().plusSeconds(30);
        var job = jobs.find("job-1");
        assertThat(
                        jobs.compareAndSet(
                                new MemoryReviewJob(
                                        job.jobId(),
                                        job.conversationId(),
                                        job.learningEpoch(),
                                        job.reviewedThroughSequence(),
                                        job.pendingThroughSequence(),
                                        job.claimedThroughSequence(),
                                        job.dirtyGeneration(),
                                        job.claimedGeneration(),
                                        job.status(),
                                        job.availableAt(),
                                        job.failureCount(),
                                        job.failureReason(),
                                        job.owner(),
                                        job.fencingToken(),
                                        renewedUntil),
                                job.fencingToken()))
                .isTrue();
        assertThat(leases.compareAndSet(new MemoryReviewLease(7, "worker-1", renewedUntil), 7))
                .isTrue();

        assertThat(jobs.validateClaim(fixture)).isTrue();
        assertThat(jobs.validateClaim(claimWith(fixture, 12, 7, "worker-1"))).isFalse();
        assertThat(jobs.validateClaim(claimWith(fixture, 11, 8, "worker-1"))).isFalse();
        assertThat(jobs.validateClaim(claimWith(fixture, 11, 7, "worker-2"))).isFalse();
    }

    @Test
    void claimExpiresByActualDatabaseClockInsideOneTransaction() {
        Boolean valid =
                transactions.execute(
                        ignored -> {
                            jdbc.queryForObject("select current_timestamp", Instant.class);
                            var claim = insertClaimFixture(Instant.now().plusMillis(400));
                            try {
                                Thread.sleep(700);
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(exception);
                            }
                            return jobs.validateClaim(claim) || leases.validateClaim(claim);
                        });
        assertThat(valid).isFalse();
    }

    @Test
    void operationAndSuppressionRoundTripWithoutCopyingDeletedContent() {
        var now = LocalDateTime.of(2026, 9, 17, 9, 0);
        var outcome =
                new MemoryOutcome(
                        MemoryStatus.REMOVED,
                        "m1",
                        2L,
                        5,
                        false,
                        false,
                        Map.of(
                                MemoryKind.PERSON_PROFILE,
                                new MemoryQuota(12, 800),
                                MemoryKind.COLLABORATION_PREFERENCE,
                                new MemoryQuota(20, 1500)));
        operations.insert(new MemoryOperation("request-1", "payload-hash", outcome, now));
        suppressions.insert(new MemorySuppression(1, "deleted-hash", 5, now, "request-1"));

        assertThat(operations.find("request-1").outcome()).isEqualTo(outcome);
        assertThat(suppressions.find("deleted-hash").deleteOperationId()).isEqualTo("request-1");
        assertThat(suppressions.deleteByHash("deleted-hash")).isTrue();
    }

    private static MemoryEntry entry(String id, String content, long version) {
        var now = LocalDateTime.of(2026, 9, 17, 9, 0);
        return new MemoryEntry(
                id,
                MemoryKind.COLLABORATION_PREFERENCE,
                content,
                "hash-" + version,
                1,
                content.length(),
                MemorySource.USER_DIRECT,
                true,
                version,
                now,
                now,
                now,
                "prompt-v1");
    }

    private MemoryReviewClaim insertClaimFixture(Instant leaseUntil) {
        var now = LocalDateTime.of(2026, 9, 17, 9, 0);
        leases.insert(new MemoryReviewLease(7, "worker-1", leaseUntil));
        jobs.insert(
                new MemoryReviewJob(
                        "job-1",
                        "conversation-1",
                        3,
                        10,
                        20,
                        20,
                        4,
                        4,
                        MemoryReviewJob.Status.RUNNING,
                        now,
                        0,
                        null,
                        "worker-1",
                        11,
                        leaseUntil));
        return new MemoryReviewClaim(
                "job-1", "conversation-1", 3, 11, 7, "worker-1", 4, 10, 20, leaseUntil);
    }

    private static MemoryReviewClaim claimWith(
            MemoryReviewClaim claim, long jobToken, long userToken, String owner) {
        return new MemoryReviewClaim(
                claim.jobId(),
                claim.conversationId(),
                claim.epoch(),
                jobToken,
                userToken,
                owner,
                claim.claimedGeneration(),
                claim.fromSequence(),
                claim.throughSequence(),
                claim.leaseUntil());
    }
}
