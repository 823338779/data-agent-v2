package com.datagent.agentv2.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagent.agentv2.conversation.runtime.InstanceRunCapacity;
import com.datagent.agentv2.conversation.runtime.RunClaimService;
import com.datagent.agentv2.conversation.runtime.RunExecutionPort;
import com.datagent.agentv2.conversation.runtime.RunInstanceIdentity;
import com.datagent.agentv2.conversation.runtime.RunOwnerRegistry;
import com.datagent.agentv2.conversation.runtime.RunRuntimeProperties;
import com.datagent.agentv2.persistence.service.AgentRunAttemptService;
import com.datagent.agentv2.persistence.service.AgentRunEventService;
import com.datagent.agentv2.persistence.service.AgentRunService;
import com.datagent.agentv2.persistence.service.AgentSessionService;
import com.datagent.agentv2.persistence.service.UserAccountService;
import com.datagent.agentv2.platform.context.TenantContext;
import com.datagent.agentv2.platform.id.IdGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = "data-agent.runtime.scheduling-enabled=false")
class RunCapacityIntegrationTest {

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
    @Autowired private AgentRunService runs;
    @Autowired private AgentRunAttemptService attempts;
    @Autowired private AgentRunEventService events;
    @Autowired private AgentSessionService sessions;
    @Autowired private UserAccountService users;
    @Autowired private IdGenerator ids;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearRuns() {
        jdbc.update("delete from agent_run_event");
        jdbc.update("delete from agent_run_stream");
        jdbc.update("delete from agent_run_attempt");
        jdbc.update("update agent_message set run_id = null");
        jdbc.update("delete from agent_run");
        jdbc.update("delete from agent_message");
        jdbc.update("delete from agent_session");
        jdbc.update("delete from user_account where user_id <> 'user_admin'");
    }

    @Test
    void oneUserCanOwnAtMostThreeRunningRuns() {
        for (int index = 1; index <= 4; index++) {
            queued(
                    "user_admin",
                    "same-user-" + index,
                    Instant.parse("2026-08-01T00:00:0" + index + "Z"));
        }
        try (RuntimeContext runtime = runtime(10)) {
            assertThat(claim(runtime)).isTrue();
            assertThat(claim(runtime)).isTrue();
            assertThat(claim(runtime)).isTrue();
            assertThat(claim(runtime)).isFalse();
            assertThat(runningFor("user_admin")).isEqualTo(3);
            assertThat(queuedFor("user_admin")).isEqualTo(1);
        }
    }

    @Test
    void fullInstanceStopsClaimingWhileAnotherContextCanClaim() {
        createUser("user_two", "two");
        createUser("user_three", "three");
        queued("user_admin", "capacity-1", Instant.parse("2026-08-01T00:00:01Z"));
        queued("user_two", "capacity-2", Instant.parse("2026-08-01T00:00:02Z"));
        queued("user_three", "capacity-3", Instant.parse("2026-08-01T00:00:03Z"));

        try (RuntimeContext first = runtime(2);
                RuntimeContext second = runtime(2)) {
            assertThat(claim(first)).isTrue();
            assertThat(claim(first)).isTrue();
            assertThat(claim(first)).isFalse();
            assertThat(first.capacity().used()).isEqualTo(2);
            assertThat(first.capacity().available()).isZero();

            assertThat(claim(second)).isTrue();
            assertThat(second.capacity().used()).isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from agent_run where status='running'",
                                    Integer.class))
                    .isEqualTo(3);
            assertThat(first.owners().size()).isEqualTo(2);
            assertThat(second.owners().size()).isEqualTo(1);
        }
    }

    @Test
    void nonPositiveRuntimeLimitsFailFast() {
        assertThatThrownBy(
                        () ->
                                new RunRuntimeProperties(
                                        0,
                                        Duration.ofSeconds(1),
                                        Duration.ofSeconds(1),
                                        Duration.ofMinutes(10),
                                        Duration.ofSeconds(60),
                                        Duration.ofSeconds(15),
                                        false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private boolean claim(RuntimeContext runtime) {
        return TenantContext.call("tenant_local", runtime.claims()::claimNext);
    }

    private RuntimeContext runtime(int capacityLimit) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RunRuntimeProperties properties =
                new RunRuntimeProperties(
                        capacityLimit,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(10),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(15),
                        false);
        context.registerBean(SimpleMeterRegistry.class, () -> meters);
        context.registerBean(RunRuntimeProperties.class, () -> properties);
        context.registerBean(
                InstanceRunCapacity.class, () -> new InstanceRunCapacity(properties, meters));
        context.registerBean(
                RunOwnerRegistry.class,
                () -> new RunOwnerRegistry(context.getBean(InstanceRunCapacity.class), meters));
        context.registerBean(HoldingExecutionPort.class);
        context.registerBean(
                RunInstanceIdentity.class,
                () -> new RunInstanceIdentity("instance_capacity_" + capacityLimit));
        context.registerBean(
                RunClaimService.class,
                () ->
                        new RunClaimService(
                                runs,
                                attempts,
                                events,
                                sessions,
                                users,
                                ids,
                                transactionManager,
                                properties,
                                context.getBean(InstanceRunCapacity.class),
                                context.getBean(RunOwnerRegistry.class),
                                context.getBean(HoldingExecutionPort.class),
                                context.getBean(RunInstanceIdentity.class)));
        context.refresh();
        return new RuntimeContext(
                context,
                context.getBean(RunClaimService.class),
                context.getBean(InstanceRunCapacity.class),
                context.getBean(RunOwnerRegistry.class));
    }

    private void createUser(String id, String username) {
        jdbc.update(
                "insert into user_account(user_id, tenant_id, username, password_hash) values (?, 'tenant_local', ?, 'hash')",
                id,
                username);
    }

    private void queued(String userId, String suffix, Instant queuedAt) {
        String sessionId = "session_" + suffix;
        String messageId = "message_" + suffix;
        String runId = "run_" + suffix;
        jdbc.update(
                "insert into agent_session(session_id, tenant_id, title, session_type, created_by) values (?, 'tenant_local', ?, 'INTERACTIVE', ?)",
                sessionId,
                suffix,
                userId);
        jdbc.update(
                "insert into agent_message(message_id, tenant_id, session_id, role, content, sequence, author_user_id) values (?, 'tenant_local', ?, 'user', ?, 1, ?)",
                messageId,
                sessionId,
                suffix,
                userId);
        jdbc.update(
                """
                insert into agent_run(
                    run_id, tenant_id, session_id, triggering_message_id, status, phase,
                    execution_attempt, owner_user_id, request_id, queued_at,
                    request_type, request_idempotency_key, request_payload_hash)
                values (?, 'tenant_local', ?, ?, 'queued', 'QUEUED', 0, ?, ?, ?,
                        'message_submission', ?, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')
                """,
                runId,
                sessionId,
                messageId,
                userId,
                "request_" + suffix,
                Timestamp.from(queuedAt),
                "key_" + suffix);
        jdbc.update("update agent_message set run_id = ? where message_id = ?", runId, messageId);
    }

    private int runningFor(String userId) {
        return jdbc.queryForObject(
                "select count(*) from agent_run where owner_user_id=? and status='running'",
                Integer.class,
                userId);
    }

    private int queuedFor(String userId) {
        return jdbc.queryForObject(
                "select count(*) from agent_run where owner_user_id=? and status='queued'",
                Integer.class,
                userId);
    }

    static final class HoldingExecutionPort implements RunExecutionPort {
        private final List<OwnedRun> started = new ArrayList<>();

        @Override
        public void start(OwnedRun run, Runnable onTerminated) {
            started.add(run);
        }
    }

    private record RuntimeContext(
            AnnotationConfigApplicationContext context,
            RunClaimService claims,
            InstanceRunCapacity capacity,
            RunOwnerRegistry owners)
            implements AutoCloseable {
        @Override
        public void close() {
            context.close();
        }
    }
}
