package com.datagent.agentv2.memory.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;

import com.datagent.agentv2.memory.*;
import com.datagent.agentv2.persistence.service.memory.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 通过 Spring 事务代理验证真实任务状态机，原子修改租约时间代替等待 TTL。 */
@SpringBootTest(classes = MemoryIntegrationTestConfiguration.class)
@Import(MemoryReviewCoordinator.class)
class MemoryReviewCoordinatorTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Autowired MemoryReviewCoordinator coordinator;
    @Autowired MemoryReviewJobService jobs;
    @Autowired MemoryReviewLeaseService leases;
    @MockitoSpyBean MemoryStateService states;
    @Autowired MemoryService memory;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    @BeforeEach
    void seed() {
        jdbc.execute("truncate tenant cascade");
        jdbc.update("insert into tenant(tenant_id,name) values ('tenant_local','Local')");
        jdbc.update(
                "insert into user_account(user_id,tenant_id,username,password_hash) values ('user_admin','tenant_local','admin','!')");
        memory.initialize();
        mark(1, 10, 20);
        due();
    }

    @Test
    void claimFreezesRangeAndRunningMarkerPreservesOwnerAndTokens() {
        var claim = claim("one");
        assertThat(claim.fromSequence()).isEqualTo(10);
        assertThat(claim.throughSequence()).isEqualTo(20);
        mark(1, 30, 40);
        var job = jobs.find("job");
        assertThat(job.owner()).isEqualTo("one");
        assertThat(job.fencingToken()).isEqualTo(claim.jobToken());
        assertThat(job.claimedThroughSequence()).isEqualTo(20);
        assertThat(job.dirtyGeneration()).isEqualTo(2);
        assertThat(coordinator.finish(claim, new MemoryReviewProgress(20, 0)))
                .isEqualTo(MemoryReviewJob.Status.PENDING);
        assertThat(jobs.find("job").reviewedThroughSequence()).isEqualTo(20);
        due();
        var next = claim("two");
        assertThat(next.fromSequence()).isEqualTo(20);
        assertThat(next.throughSequence()).isEqualTo(40);
        assertThat(coordinator.finish(next, new MemoryReviewProgress(40, 2)))
                .isEqualTo(MemoryReviewJob.Status.COMPLETED);
        assertThat(leases.findCurrent().owner()).isNull();
        assertThat(leases.findCurrent().fencingToken()).isEqualTo(2);
    }

    @Test
    void renewalKeepsOldClaimValidAndExpiredOwnerCannotRevive() {
        var old = claim("one");
        assertThat(coordinator.renew(old)).isTrue();
        assertThat(jobs.validateClaim(old)).isTrue();
        expire();
        assertThat(coordinator.renew(old)).isFalse();
        var next = claim("two");
        assertThat(next.jobToken()).isEqualTo(old.jobToken() + 1);
        assertThat(next.userToken()).isEqualTo(old.userToken() + 1);
        assertThatThrownBy(() -> coordinator.finish(old, new MemoryReviewProgress(20, 0)))
                .isInstanceOfSatisfying(
                        MemoryException.class,
                        e -> assertThat(e.code()).isEqualTo(MemoryStatus.LEASE_LOST));
        coordinator.fail(old, "OLD", false);
        coordinator.release(old);
        assertThat(jobs.find("job").owner()).isEqualTo("two");
        assertThat(coordinator.finish(next, new MemoryReviewProgress(20, 0)))
                .isEqualTo(MemoryReviewJob.Status.COMPLETED);
    }

    @Test
    void finishNeverMovesBeyondFrozenBoundaryOrBackwards() {
        var claim = claim("one");
        assertThatThrownBy(() -> coordinator.finish(claim, new MemoryReviewProgress(21, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> coordinator.finish(claim, new MemoryReviewProgress(9, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(coordinator.finish(claim, new MemoryReviewProgress(15, 0)))
                .isEqualTo(MemoryReviewJob.Status.PENDING);
        assertThat(jobs.find("job").reviewedThroughSequence()).isEqualTo(15);
    }

    @Test
    void disablingLearningCancelsOldEpochWithoutCallingItCompleted() {
        var claim = claim("one");
        memory.updateSettings("off", new MemorySettingsChange(0, null, null, false));
        assertThat(coordinator.finish(claim, new MemoryReviewProgress(20, 0)))
                .isEqualTo(MemoryReviewJob.Status.CANCELLED);
        assertThat(jobs.find("job").reviewedThroughSequence()).isEqualTo(10);
        assertThat(leases.findCurrent().owner()).isNull();
    }

    @Test
    void newEpochInvalidatesOldClaimAndLowerEpochMarkerCannotEnlargeRange() {
        var old = claim("one");
        memory.updateSettings("off", new MemorySettingsChange(0, null, null, false));
        memory.updateSettings("on", new MemorySettingsChange(1, null, null, true));
        mark(3, 50, 60);
        var newJob = jobs.find("job");
        assertThat(newJob.learningEpoch()).isEqualTo(3);
        assertThat(newJob.reviewedThroughSequence()).isEqualTo(50);
        assertThat(newJob.fencingToken()).isGreaterThan(old.jobToken());
        mark(1, 10, 100);
        assertThat(jobs.find("job")).isEqualTo(newJob);
        assertThatThrownBy(() -> coordinator.finish(old, new MemoryReviewProgress(20, 0)))
                .isInstanceOf(MemoryException.class);
    }

    @Test
    void failuresAreBoundedAndNewEventReactivatesWithoutSkippingCursor() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            coordinator.fail(claim("one"), "MODEL_UNAVAILABLE", false);
            assertThat(jobs.find("job").failureCount()).isEqualTo(attempt);
            assertThat(jobs.find("job").reviewedThroughSequence()).isEqualTo(10);
            assertThat(jobs.find("job").status())
                    .isEqualTo(
                            attempt < 3
                                    ? MemoryReviewJob.Status.FAILED
                                    : MemoryReviewJob.Status.BLOCKED);
            due();
        }
        assertThat(coordinator.claimDue("two", 4)).isEmpty();
        mark(1, 30, 40);
        assertThat(jobs.find("job").failureCount()).isZero();
        due();
        var claim = claim("two");
        assertThat(claim.fromSequence()).isEqualTo(10);
        coordinator.fail(claim, "INPUT_TOO_LARGE", true);
        assertThat(jobs.find("job").status()).isEqualTo(MemoryReviewJob.Status.BLOCKED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void obsoleteBlockedJobIsCancelledWithoutNewConversationEvent(boolean reenable) {
        var claim = claim("worker");
        coordinator.fail(claim, "INPUT_TOO_LARGE", true);
        memory.updateSettings("off", new MemorySettingsChange(0, null, null, false));
        if (reenable) memory.updateSettings("on", new MemorySettingsChange(1, null, null, true));

        assertThat(coordinator.claimDue("scanner", 1)).isEmpty();
        assertThat(jobs.find("job").status()).isEqualTo(MemoryReviewJob.Status.CANCELLED);
        assertThat(jobs.find("job").reviewedThroughSequence()).isEqualTo(10);
        assertThat(leases.findCurrent().fencingToken()).isEqualTo(claim.userToken());
    }

    @ParameterizedTest
    @EnumSource(
            value = MemoryReviewJob.Status.class,
            names = {"PENDING", "FAILED", "RUNNING"})
    void obsoleteUnfinishedJobCancellationDoesNotWaitForDueTimeOrLease(
            MemoryReviewJob.Status status) {
        jdbc.update(
                "update personal_memory_review_job set status=?, available_at=clock_timestamp() at time zone 'Asia/Shanghai'+interval '1 day', owner='old-worker', fencing_token=1, lease_until=clock_timestamp()+interval '1 day'",
                status.name());
        memory.updateSettings("off", new MemorySettingsChange(0, null, null, false));

        assertThat(coordinator.claimDue("scanner", 1)).isEmpty();
        assertThat(jobs.find("job").status()).isEqualTo(MemoryReviewJob.Status.CANCELLED);
        assertThat(jobs.find("job").owner()).isNull();
        assertThat(jobs.find("job").leaseUntil()).isNull();
        assertThat(jobs.find("job").reviewedThroughSequence()).isEqualTo(10);
    }

    @Test
    void cancellationBacklogAndCurrentBlockedJobsDoNotConsumeReadyJobLimit() {
        memory.updateSettings("off", new MemorySettingsChange(0, null, null, false));
        memory.updateSettings("on", new MemorySettingsChange(1, null, null, true));
        mark(3, 10, 20);
        due();
        for (int index = 0; index < 3; index++) {
            for (long epoch : List.of(1L, 3L)) {
                String id = "blocked-" + epoch + "-" + index;
                jobs.insert(
                        new MemoryReviewJob(
                                id,
                                id,
                                epoch,
                                0,
                                2,
                                0,
                                1,
                                0,
                                MemoryReviewJob.Status.BLOCKED,
                                states.currentBusinessTime().minusDays(1),
                                1,
                                "INPUT_TOO_LARGE",
                                null,
                                0,
                                null));
            }
        }

        var claimed = coordinator.claimDue("worker", 1);
        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().jobId()).isEqualTo("job");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from personal_memory_review_job where learning_epoch=1 and status='CANCELLED'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from personal_memory_review_job where learning_epoch=3 and status='BLOCKED'",
                                Integer.class))
                .isEqualTo(3);
        coordinator.finish(claimed.getFirst(), new MemoryReviewProgress(20, 0));
        assertThat(coordinator.claimDue("scanner", 1)).isEmpty();
        assertThat(coordinator.claimDue("scanner", 1)).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from personal_memory_review_job where learning_epoch=1 and status='CANCELLED'",
                                Integer.class))
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from personal_memory_review_job where learning_epoch=3 and status='BLOCKED'",
                                Integer.class))
                .isEqualTo(3);
    }

    @Test
    void candidateCompletedBeforeEpochLockMustKeepCompletedStatus() {
        doAnswer(
                        invocation -> {
                            // 模拟无锁选候选后、拿 state 锁前，任务先完成且学习代际随后关闭。
                            jdbc.update(
                                    "update personal_memory_review_job set status='COMPLETED', reviewed_through_sequence=20, claimed_through_sequence=20");
                            jdbc.update(
                                    "update personal_memory_state set learning_epoch=2, auto_learning_enabled=false, learning_enabled_at=null");
                            return invocation.callRealMethod();
                        })
                .when(states)
                .lockCurrent();

        assertThat(coordinator.claimDue("scanner", 1)).isEmpty();
        assertThat(jobs.find("job").status()).isEqualTo(MemoryReviewJob.Status.COMPLETED);
        assertThat(jobs.find("job").reviewedThroughSequence()).isEqualTo(20);
    }

    @Test
    void releasePreservesTokenAndCursorAndClearsNullableOwnership() {
        var claim = claim("one");
        coordinator.release(claim);
        assertThat(jobs.find("job").status()).isEqualTo(MemoryReviewJob.Status.PENDING);
        assertThat(jobs.find("job").owner()).isNull();
        assertThat(jobs.find("job").leaseUntil()).isNull();
        assertThat(leases.findCurrent().fencingToken()).isEqualTo(claim.userToken());
        assertThat(leases.findCurrent().leaseUntil()).isNull();
    }

    @Test
    void cancellingOldJobDoesNotReleaseAnotherJobsNewTokenWithSameOwner() {
        claim("same-worker");
        expire();
        memory.updateSettings("off", new MemorySettingsChange(0, null, null, false));
        memory.updateSettings("on", new MemorySettingsChange(1, null, null, true));
        // 同一进程的 owner 可以复用，但新的用户租约 token 属于另一任务。
        jdbc.update(
                "update personal_memory_review_lease set fencing_token=2, lease_until=clock_timestamp()+interval '120 seconds'");
        assertThat(coordinator.claimDue("scanner", 4)).isEmpty();
        assertThat(jobs.find("job").status()).isEqualTo(MemoryReviewJob.Status.CANCELLED);
        assertThat(leases.findCurrent().owner()).isEqualTo("same-worker");
        assertThat(leases.findCurrent().fencingToken()).isEqualTo(2);
    }

    @Test
    void forgedUserTokenCannotFinishOrReleaseAnOtherwiseMatchingJob() {
        var claim = claim("one");
        var forged =
                new MemoryReviewClaim(
                        claim.jobId(),
                        claim.conversationId(),
                        claim.epoch(),
                        claim.jobToken(),
                        claim.userToken() + 1,
                        claim.owner(),
                        claim.claimedGeneration(),
                        claim.fromSequence(),
                        claim.throughSequence(),
                        claim.leaseUntil());
        assertThat(coordinator.renew(forged)).isFalse();
        assertThatThrownBy(() -> coordinator.finish(forged, new MemoryReviewProgress(20, 0)))
                .isInstanceOfSatisfying(
                        MemoryException.class,
                        e -> assertThat(e.code()).isEqualTo(MemoryStatus.LEASE_LOST));
        coordinator.release(forged);
        assertThat(jobs.find("job").status()).isEqualTo(MemoryReviewJob.Status.RUNNING);
        assertThat(leases.findCurrent().owner()).isEqualTo("one");
    }

    @Test
    void renewalCannotReviveLeaseExpiringBetweenValidationAndUpdate() {
        var claim = claim("one");
        doAnswer(
                        invocation -> {
                            expire();
                            return invocation.callRealMethod();
                        })
                .when(states)
                .currentBusinessTime();
        assertThat(coordinator.renew(claim)).isFalse();
        assertThat(jobs.find("job").leaseUntil()).isEqualTo(claim.leaseUntil());
    }

    @Test
    void userLeaseExpirationAfterValidationRollsBackPartialFinish() {
        var claim = claim("one");
        doAnswer(
                        invocation -> {
                            jdbc.update(
                                    "update personal_memory_review_lease set lease_until=clock_timestamp()-interval '1 second'");
                            return invocation.callRealMethod();
                        })
                .when(states)
                .currentBusinessTime();
        assertThatThrownBy(() -> coordinator.finish(claim, new MemoryReviewProgress(20, 0)))
                .isInstanceOfSatisfying(
                        MemoryException.class,
                        e -> assertThat(e.code()).isEqualTo(MemoryStatus.LEASE_LOST));
        assertThat(jobs.find("job").reviewedThroughSequence()).isEqualTo(10);
        assertThat(jobs.find("job").status()).isEqualTo(MemoryReviewJob.Status.RUNNING);
    }

    @Test
    void concurrentWorkersCannotOwnDifferentJobsForSameUser() throws Exception {
        transactions.executeWithoutResult(
                status -> {
                    states.lockCurrent();
                    jobs.markCompleted(
                            "other-job",
                            "other-conversation",
                            1,
                            0,
                            2,
                            states.currentBusinessTime().minusSeconds(1));
                });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var one =
                    workers.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return coordinator.claimDue("one", 4);
                            });
            var two =
                    workers.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                return coordinator.claimDue("two", 4);
                            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var first = one.get(15, TimeUnit.SECONDS);
            var second = two.get(15, TimeUnit.SECONDS);
            assertThat(first.size() + second.size()).isEqualTo(1);
        }
    }

    void mark(long epoch, long from, long through) {
        transactions.executeWithoutResult(
                status -> {
                    states.lockCurrent();
                    jobs.markCompleted(
                            "job",
                            "conversation",
                            epoch,
                            from,
                            through,
                            states.currentBusinessTime().plusSeconds(30));
                });
    }

    void due() {
        jdbc.update(
                "update personal_memory_review_job set available_at=clock_timestamp() at time zone 'Asia/Shanghai' - interval '1 second'");
    }

    void expire() {
        jdbc.update(
                "update personal_memory_review_job set lease_until=clock_timestamp()-interval '1 second'");
        jdbc.update(
                "update personal_memory_review_lease set lease_until=clock_timestamp()-interval '1 second'");
    }

    MemoryReviewClaim claim(String owner) {
        List<MemoryReviewClaim> claims = coordinator.claimDue(owner, 4);
        assertThat(claims).hasSize(1);
        return claims.getFirst();
    }
}
