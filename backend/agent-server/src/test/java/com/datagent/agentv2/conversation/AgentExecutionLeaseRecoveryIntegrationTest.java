package com.datagent.agentv2.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.conversation.runtime.AgentExecutionLeaseService;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort.ClaimedAgentExecution;
import com.datagent.agentv2.conversation.runtime.AgentExecutionRecoveryScheduler;
import com.datagent.agentv2.conversation.runtime.ExecutionDeadlineExceededException;
import com.datagent.agentv2.conversation.runtime.ExecutionOwnershipLostException;
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
class AgentExecutionLeaseRecoveryIntegrationTest {

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
    @Autowired private AgentExecutionLeaseService leases;
    @Autowired private AgentExecutionRecoveryScheduler recovery;

    @BeforeEach
    void clearExecutions() {
        jdbc.update("delete from agent_execution_event");
        jdbc.update("delete from agent_execution_stream_checkpoint");
        jdbc.update("delete from agent_execution_attempt");
        jdbc.update("update conversation_message set execution_id = null");
        jdbc.update("delete from agent_execution");
        jdbc.update("delete from conversation_message");
        jdbc.update("delete from conversation_session");
    }

    @Test
    void heartbeatRenewsLeaseAndOldOwnerIsFencedAfterTakeover() {
        Instant now = Instant.now();
        ClaimedAgentExecution oldOwner =
                running("lease", "instance_old", now.minusSeconds(1), 1_000L, 1L);

        assertThat(inTenant(() -> leases.heartbeat(oldOwner))).isTrue();
        Instant renewed =
                jdbc.queryForObject(
                                "select lease_expires_at from agent_execution where execution_id='execution_lease'",
                                Timestamp.class)
                        .toInstant();
        assertThat(renewed).isAfter(Instant.now().plusSeconds(50));

        jdbc.update(
                "update agent_execution set lease_expires_at=?, active_segment_started_at=? where execution_id='execution_lease'",
                Timestamp.from(Instant.now().minusSeconds(1)),
                Timestamp.from(Instant.now().minusMillis(100)));
        assertThat(inTenant(recovery::recoverNext)).isTrue();

        assertThat(inTenant(() -> leases.heartbeat(oldOwner))).isFalse();
        assertThatThrownBy(
                        () ->
                                TenantContext.run(
                                        "tenant_local",
                                        () -> leases.assertOwnedAndWithinBudget(oldOwner)))
                .isInstanceOf(ExecutionOwnershipLostException.class);
        assertThat(
                        jdbc.queryForObject(
                                "select execution_attempt_number from agent_execution where execution_id='execution_lease'",
                                Long.class))
                .isEqualTo(2L);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from agent_execution_attempt where execution_id='execution_lease'",
                                Integer.class))
                .isEqualTo(2);
    }

    @Test
    void exhaustedActiveBudgetFailsInsteadOfBeingTakenOver() {
        Instant now = Instant.now();
        running("budget", "instance_old", now.minusSeconds(1), 599_900L, 1L);
        jdbc.update(
                "update agent_execution set active_segment_started_at=? where execution_id='execution_budget'",
                Timestamp.from(Instant.now().minusSeconds(1)));

        assertThat(inTenant(recovery::recoverNext)).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "select status from agent_execution where execution_id='execution_budget'",
                                String.class))
                .isEqualTo("failed");
        assertThat(
                        jdbc.queryForObject(
                                "select failure_code from agent_execution where execution_id='execution_budget'",
                                String.class))
                .isEqualTo("EXECUTION_DEADLINE_EXCEEDED");
    }

    @Test
    void executionBoundaryAtomicallyFailsAnClaimedAgentExecutionPastItsDeadline() {
        ClaimedAgentExecution owned =
                running("boundary", "instance_boundary", Instant.now().plusSeconds(60), 10L, 1L);
        jdbc.update(
                "update agent_execution set hard_deadline_at=? where execution_id='execution_boundary'",
                Timestamp.from(Instant.now().minusMillis(1)));

        assertThatThrownBy(
                        () ->
                                TenantContext.run(
                                        "tenant_local",
                                        () -> leases.assertOwnedAndWithinBudget(owned)))
                .isInstanceOf(ExecutionDeadlineExceededException.class);
        assertThat(
                        jdbc.queryForObject(
                                "select status from agent_execution where execution_id='execution_boundary'",
                                String.class))
                .isEqualTo("failed");
    }

    private ClaimedAgentExecution running(
            String suffix,
            String ownerInstanceId,
            Instant leaseExpiresAt,
            long activeExecutionMs,
            long attempt) {
        String conversationSessionId = "session_" + suffix;
        String conversationMessageId = "message_" + suffix;
        String executionId = "execution_" + suffix;
        jdbc.update(
                "insert into conversation_session(conversation_session_id, tenant_id, title, session_type, created_by_user_id) values (?, 'tenant_local', ?, 'INTERACTIVE', 'user_admin')",
                conversationSessionId,
                suffix);
        jdbc.update(
                "insert into conversation_message(conversation_message_id, tenant_id, conversation_session_id, role, content, conversation_message_sequence, author_user_id) values (?, 'tenant_local', ?, 'user', ?, 1, 'user_admin')",
                conversationMessageId,
                conversationSessionId,
                suffix);
        Instant now = Instant.now();
        jdbc.update(
                """
                insert into agent_execution(
                    execution_id, tenant_id, conversation_session_id, triggering_conversation_message_id, status, phase,
                    execution_attempt_number, owner_user_id, owner_instance_id, request_id, queued_at,
                    lease_expires_at, heartbeat_at, active_segment_started_at, started_at,
                    hard_deadline_at, active_execution_ms)
                values (?, 'tenant_local', ?, ?, 'running', 'RECEIVED', ?, 'user_admin', ?, ?, ?,
                        ?, ?, ?, ?, ?, ?)
                """,
                executionId,
                conversationSessionId,
                conversationMessageId,
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
        jdbc.update(
                "update conversation_message set execution_id=? where conversation_message_id=?",
                executionId,
                conversationMessageId);
        jdbc.update(
                "insert into agent_execution_attempt(execution_attempt_id, tenant_id, execution_id, execution_attempt_number, owner_instance_id, status, started_at) values (?, 'tenant_local', ?, ?, ?, 'running', ?)",
                "attempt_" + suffix,
                executionId,
                attempt,
                ownerInstanceId,
                Timestamp.from(now.minusSeconds(1)));
        return new ClaimedAgentExecution("tenant_local", executionId, ownerInstanceId, attempt);
    }

    private <T> T inTenant(java.util.function.Supplier<T> action) {
        return TenantContext.call("tenant_local", action);
    }
}
