package com.datagent.agentv2.memory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

import com.datagent.agentv2.persistence.service.memory.MemoryEntryService;
import com.datagent.agentv2.persistence.service.memory.MemoryOperationService;
import com.datagent.agentv2.persistence.service.memory.MemoryReviewJobService;
import com.datagent.agentv2.persistence.service.memory.MemoryReviewLeaseService;
import com.datagent.agentv2.persistence.service.memory.MemorySuppressionService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 生命周期与设置共用真实 PostgreSQL 事务夹具；容器由 Testcontainers 在测试 JVM 结束时回收。 */
@SpringBootTest(classes = MemoryIntegrationTestConfiguration.class)
abstract class MemoryLifecycleSupport {
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

    @Autowired MemoryService memory;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemoryEvidencePort evidencePort;
    @Autowired MemoryOperationService operations;
    @Autowired MemoryEntryService entries;
    @Autowired MemoryReviewJobService jobs;
    @Autowired MemoryReviewLeaseService leases;
    @Autowired MemorySuppressionService suppressions;
    @Autowired MemoryContentPolicy contentPolicy;

    @BeforeEach
    void seed() {
        jdbc.execute("truncate tenant cascade");
        jdbc.update("insert into tenant(tenant_id,name) values ('tenant_local','Local')");
        jdbc.update(
                "insert into user_account(user_id,tenant_id,username,password_hash) values ('user_admin','tenant_local','admin','!')");
        reset(evidencePort);
    }

    MemoryReviewClaim claimFixture() {
        var until =
                Instant.now().plusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
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

    void stubEvidence(MemoryEvidence evidence) {
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
