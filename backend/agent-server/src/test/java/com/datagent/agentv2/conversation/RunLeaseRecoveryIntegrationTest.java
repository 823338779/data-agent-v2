package com.datagent.agentv2.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.conversation.runtime.RunDeadlineExceededException;
import com.datagent.agentv2.conversation.runtime.RunExecutionPort.OwnedRun;
import com.datagent.agentv2.conversation.runtime.RunLeaseService;
import com.datagent.agentv2.conversation.runtime.RunOwnershipLostException;
import com.datagent.agentv2.conversation.runtime.RunRecoveryScheduler;
import com.datagent.agentv2.platform.context.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = "data-agent.runtime.scheduling-enabled=false")
class RunLeaseRecoveryIntegrationTest {

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
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RunLeaseService leases;
    @Autowired private RunRecoveryScheduler recovery;

    @BeforeEach
    void clearRuns() {
        jdbc.update("delete from agent_run_event");
        jdbc.update("delete from agent_run_stream");
        jdbc.update("delete from agent_run_attempt");
        jdbc.update("update agent_message set run_id = null");
        jdbc.update("delete from agent_run");
        jdbc.update("delete from agent_message");
        jdbc.update("delete from agent_session");
    }

    @Test
    void heartbeatRenewsLeaseAndOldOwnerIsFencedAfterTakeover() {
        Instant now = Instant.now();
        OwnedRun oldOwner = running("lease", "instance_old", now.minusSeconds(1), 1_000L, 1L);

        assertThat(inTenant(() -> leases.heartbeat(oldOwner))).isTrue();
        Instant renewed =
                jdbc.queryForObject(
                                "select lease_expires_at from agent_run where uuid='run_lease'",
                                Timestamp.class)
                        .toInstant();
        assertThat(renewed).isAfter(Instant.now().plusSeconds(50));

        jdbc.update(
                "update agent_run set lease_expires_at=?, active_segment_started_at=? where uuid='run_lease'",
                Timestamp.from(Instant.now().minusSeconds(1)),
                Timestamp.from(Instant.now().minusMillis(100)));
        assertThat(inTenant(recovery::recoverNext)).isTrue();

        assertThat(inTenant(() -> leases.heartbeat(oldOwner))).isFalse();
        assertThatThrownBy(
                        () ->
                                TenantContext.run(
                                        "tenant_local",
                                        () -> leases.assertOwnedAndWithinBudget(oldOwner)))
                .isInstanceOf(RunOwnershipLostException.class);
        assertThat(
                        jdbc.queryForObject(
                                "select execution_attempt from agent_run where uuid='run_lease'",
                                Long.class))
                .isEqualTo(2L);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from agent_run_attempt where run_id='run_lease'",
                                Integer.class))
                .isEqualTo(2);
    }

    @Test
    void exhaustedActiveBudgetFailsInsteadOfBeingTakenOver() {
        Instant now = Instant.now();
        running("budget", "instance_old", now.minusSeconds(1), 599_900L, 1L);
        jdbc.update(
                "update agent_run set active_segment_started_at=? where uuid='run_budget'",
                Timestamp.from(Instant.now().minusSeconds(1)));

        assertThat(inTenant(recovery::recoverNext)).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "select status from agent_run where uuid='run_budget'",
                                String.class))
                .isEqualTo("failed");
        assertThat(
                        jdbc.queryForObject(
                                "select failure_code from agent_run where uuid='run_budget'",
                                String.class))
                .isEqualTo("RUN_DEADLINE_EXCEEDED");
    }

    @Test
    void executionBoundaryAtomicallyFailsAnOwnedRunPastItsDeadline() {
        OwnedRun owned =
                running("boundary", "instance_boundary", Instant.now().plusSeconds(60), 10L, 1L);
        jdbc.update(
                "update agent_run set hard_deadline_at=? where uuid='run_boundary'",
                Timestamp.from(Instant.now().minusMillis(1)));

        assertThatThrownBy(
                        () ->
                                TenantContext.run(
                                        "tenant_local",
                                        () -> leases.assertOwnedAndWithinBudget(owned)))
                .isInstanceOf(RunDeadlineExceededException.class);
        assertThat(
                        jdbc.queryForObject(
                                "select status from agent_run where uuid='run_boundary'",
                                String.class))
                .isEqualTo("failed");
    }

    private OwnedRun running(
            String suffix,
            String ownerInstanceId,
            Instant leaseExpiresAt,
            long activeExecutionMs,
            long attempt) {
        String sessionId = "session_" + suffix;
        String messageId = "message_" + suffix;
        String runId = "run_" + suffix;
        jdbc.update(
                "insert into agent_session(uuid, tenant_id, title, session_type, created_by) values (?, 'tenant_local', ?, 'INTERACTIVE', 'user_admin')",
                sessionId,
                suffix);
        jdbc.update(
                "insert into agent_message(uuid, tenant_id, session_id, role, content, sequence, author_user_id) values (?, 'tenant_local', ?, 'user', ?, 1, 'user_admin')",
                messageId,
                sessionId,
                suffix);
        Instant now = Instant.now();
        jdbc.update(
                """
                insert into agent_run(
                    uuid, tenant_id, session_id, triggering_message_id, status, phase,
                    execution_attempt, owner_user_id, owner_instance_id, request_id, queued_at,
                    lease_expires_at, heartbeat_at, active_segment_started_at, started_at,
                    hard_deadline_at, active_execution_ms)
                values (?, 'tenant_local', ?, ?, 'running', 'RECEIVED', ?, 'user_admin', ?, ?, ?,
                        ?, ?, ?, ?, ?, ?)
                """,
                runId,
                sessionId,
                messageId,
                attempt,
                ownerInstanceId,
                "request_" + suffix,
                Timestamp.from(now.minusSeconds(2)),
                Timestamp.from(leaseExpiresAt),
                Timestamp.from(now.minusSeconds(1)),
                Timestamp.from(now.minusMillis(100)),
                Timestamp.from(now.minusSeconds(1)),
                Timestamp.from(now.plusSeconds(600)),
                activeExecutionMs);
        jdbc.update("update agent_message set run_id=? where uuid=?", runId, messageId);
        jdbc.update(
                "insert into agent_run_attempt(uuid, tenant_id, run_id, execution_attempt, owner_instance_id, status, started_at) values (?, 'tenant_local', ?, ?, ?, 'running', ?)",
                "attempt_" + suffix,
                runId,
                attempt,
                ownerInstanceId,
                Timestamp.from(now.minusSeconds(1)));
        return new OwnedRun("tenant_local", runId, ownerInstanceId, attempt);
    }

    private <T> T inTenant(java.util.function.Supplier<T> action) {
        return TenantContext.call("tenant_local", action);
    }
}
