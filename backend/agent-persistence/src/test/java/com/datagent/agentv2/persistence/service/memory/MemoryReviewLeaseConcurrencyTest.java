package com.datagent.agentv2.persistence.service.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.memory.MemoryReviewClaim;
import com.datagent.agentv2.memory.MemoryReviewJob;
import com.datagent.agentv2.memory.MemoryReviewLease;
import com.datagent.agentv2.persistence.AgentPersistenceTestApplication;
import com.datagent.agentv2.persistence.mapping.MemoryPersistenceMapperImpl;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

/** 真实 PostgreSQL 连接上的 fencing 与事务内时钟回归。 */
@SpringBootTest(classes = AgentPersistenceTestApplication.class)
@Import({
    MemoryStateService.class,
    MemoryReviewJobService.class,
    MemoryReviewLeaseService.class,
    MemoryPersistenceMapperImpl.class
})
class MemoryReviewLeaseConcurrencyTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

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

    @Autowired JdbcTemplate jdbc;
    @Autowired MemoryReviewJobService jobs;
    @Autowired MemoryReviewLeaseService leases;
    @Autowired TransactionTemplate transactions;
    MemoryReviewClaim claim;

    @BeforeEach
    void seed() {
        jdbc.execute("truncate tenant cascade");
        jdbc.update("insert into tenant(tenant_id,name) values ('tenant_local','Local')");
        jdbc.update(
                "insert into user_account(user_id,tenant_id,username,password_hash) values ('user_admin','tenant_local','admin','!')");
        Instant until =
                jdbc.queryForObject(
                        "select clock_timestamp() + interval '120 seconds'", Instant.class);
        leases.insert(new MemoryReviewLease(1, "old-worker", until));
        jobs.insert(
                new MemoryReviewJob(
                        "job-1",
                        "conversation-1",
                        1,
                        10,
                        40,
                        40,
                        1,
                        1,
                        MemoryReviewJob.Status.RUNNING,
                        LocalDateTime.of(2026, 1, 1, 0, 0),
                        0,
                        null,
                        "old-worker",
                        1,
                        until));
        claim =
                new MemoryReviewClaim(
                        "job-1", "conversation-1", 1, 1, 1, "old-worker", 1, 10, 40, until);
    }

    @Test
    void nonRunningJobCannotAuthorizeBackgroundWrites() {
        jdbc.update("update personal_memory_review_job set status='CANCELLED'");
        assertThat(jobs.validateClaim(claim)).isFalse();
    }

    @Test
    void takeoverOnAnotherConnectionFencesOldOwner() throws Exception {
        try (var connection = POSTGRES.createConnection("")) {
            int firstPid;
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("select pg_backend_pid()")) {
                rows.next();
                firstPid = rows.getInt(1);
            }
            try (var workers = Executors.newSingleThreadExecutor()) {
                int secondPid =
                        workers.submit(
                                        () ->
                                                transactions.execute(
                                                        status -> {
                                                            int pid =
                                                                    jdbc.queryForObject(
                                                                            "select pg_backend_pid()",
                                                                            Integer.class);
                                                            jdbc.update(
                                                                    "update personal_memory_review_job set lease_until=clock_timestamp()-interval '1 second'");
                                                            jdbc.update(
                                                                    "update personal_memory_review_lease set lease_until=clock_timestamp()-interval '1 second'");
                                                            jdbc.update(
                                                                    "update personal_memory_review_job set owner='new-worker', fencing_token=2, lease_until=clock_timestamp()+interval '120 seconds'");
                                                            jdbc.update(
                                                                    "update personal_memory_review_lease set owner='new-worker', fencing_token=2, lease_until=clock_timestamp()+interval '120 seconds'");
                                                            return pid;
                                                        }))
                                .get(10, TimeUnit.SECONDS);
                assertThat(secondPid).isNotEqualTo(firstPid);
            }
            try (var statement = connection.createStatement()) {
                assertThat(
                                statement.executeUpdate(
                                        "update personal_memory_review_job set reviewed_through_sequence=40 where job_id='job-1' and owner='old-worker' and fencing_token=1 and lease_until>clock_timestamp()"))
                        .isZero();
            }
        }
        assertThat(jobs.validateClaim(claim)).isFalse();
        assertThat(leases.validateClaim(claim)).isFalse();
    }

    @Test
    void repeatedValidationDoesNotReuseMybatisClockResult() {
        transactions.executeWithoutResult(
                status -> {
                    assertThat(jobs.validateClaim(claim)).isTrue();
                    assertThat(leases.validateClaim(claim)).isTrue();
                    // JDBC 绕过 MyBatis 缓存失效，必须由时钟查询自己刷新一级缓存。
                    jdbc.update(
                            "update personal_memory_review_job set lease_until=clock_timestamp()-interval '1 second'");
                    jdbc.update(
                            "update personal_memory_review_lease set lease_until=clock_timestamp()-interval '1 second'");
                    assertThat(jobs.validateClaim(claim)).isFalse();
                    assertThat(leases.validateClaim(claim)).isFalse();
                });
    }
}
