package com.datagent.agentv2.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.datagent.agentv2.conversation.runtime.AgentExecutionClaimService;
import com.datagent.agentv2.conversation.runtime.AgentExecutionClaimTransactionService;
import com.datagent.agentv2.conversation.runtime.AgentExecutionPort;
import com.datagent.agentv2.conversation.runtime.AgentExecutionRuntimeProperties;
import com.datagent.agentv2.conversation.runtime.ExecutionOwnerRegistry;
import com.datagent.agentv2.conversation.runtime.ExecutionWorkerCapacity;
import com.datagent.agentv2.conversation.runtime.ExecutionWorkerIdentity;
import com.datagent.agentv2.conversation.stream.ExecutionStreamCheckpointCoordinator;
import com.datagent.agentv2.persistence.service.AgentExecutionAttemptService;
import com.datagent.agentv2.persistence.service.AgentExecutionEventService;
import com.datagent.agentv2.persistence.service.AgentExecutionService;
import com.datagent.agentv2.persistence.service.ConversationSessionService;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = "data-agent.runtime.scheduling-enabled=false")
class ExecutionWorkerCapacityIntegrationTest {

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
    @Autowired private AgentExecutionService agentExecutions;
    @Autowired private AgentExecutionAttemptService attempts;
    @Autowired private AgentExecutionEventService events;
    @Autowired private ConversationSessionService sessions;
    @Autowired private UserAccountService users;
    @Autowired private IdGenerator ids;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearExecutions() {
        jdbc.update("delete from agent_execution_event");
        jdbc.update("delete from agent_execution_stream_checkpoint");
        jdbc.update("delete from agent_execution_attempt");
        jdbc.update("update conversation_message set execution_id = null");
        jdbc.update("delete from agent_execution");
        jdbc.update("delete from conversation_message");
        jdbc.update("delete from conversation_session");
        jdbc.update("delete from user_account where user_id <> 'user_admin'");
    }

    @Test
    void oneUserCanOwnAtMostThreeRunningExecutions() {
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
                                    "select count(*) from agent_execution where status='running'",
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
                                new AgentExecutionRuntimeProperties(
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
        AgentExecutionRuntimeProperties properties =
                new AgentExecutionRuntimeProperties(
                        capacityLimit,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(10),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(15),
                        false);
        context.register(TransactionTestConfiguration.class);
        context.registerBean(
                "transactionManager", PlatformTransactionManager.class, () -> transactionManager);
        context.registerBean(SimpleMeterRegistry.class, () -> meters);
        context.registerBean(AgentExecutionRuntimeProperties.class, () -> properties);
        context.registerBean(
                ExecutionWorkerCapacity.class,
                () -> new ExecutionWorkerCapacity(properties, meters));
        context.registerBean(
                ExecutionStreamCheckpointCoordinator.class,
                () -> mock(ExecutionStreamCheckpointCoordinator.class));
        context.registerBean(
                ExecutionOwnerRegistry.class,
                () ->
                        new ExecutionOwnerRegistry(
                                context.getBean(ExecutionWorkerCapacity.class),
                                meters,
                                context.getBean(ExecutionStreamCheckpointCoordinator.class)));
        context.registerBean(HoldingExecutionPort.class);
        context.registerBean(
                ExecutionWorkerIdentity.class,
                () -> new ExecutionWorkerIdentity("instance_capacity_" + capacityLimit));
        context.registerBean(
                AgentExecutionClaimTransactionService.class,
                () ->
                        new AgentExecutionClaimTransactionService(
                                agentExecutions,
                                attempts,
                                events,
                                sessions,
                                users,
                                ids,
                                properties,
                                context.getBean(ExecutionWorkerIdentity.class)));
        context.registerBean(
                AgentExecutionClaimService.class,
                () ->
                        new AgentExecutionClaimService(
                                agentExecutions,
                                context.getBean(AgentExecutionClaimTransactionService.class),
                                context.getBean(ExecutionWorkerCapacity.class),
                                context.getBean(ExecutionOwnerRegistry.class),
                                context.getBean(HoldingExecutionPort.class),
                                context.getBean(ExecutionWorkerIdentity.class)));
        context.refresh();
        return new RuntimeContext(
                context,
                context.getBean(AgentExecutionClaimService.class),
                context.getBean(ExecutionWorkerCapacity.class),
                context.getBean(ExecutionOwnerRegistry.class));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionTestConfiguration {}

    private void createUser(String id, String username) {
        jdbc.update(
                "insert into user_account(user_id, tenant_id, username, password_hash) values (?, 'tenant_local', ?, 'hash')",
                id,
                username);
    }

    private void queued(String userId, String suffix, Instant queuedAt) {
        String conversationSessionId = "session_" + suffix;
        String conversationMessageId = "message_" + suffix;
        String executionId = "execution_" + suffix;
        jdbc.update(
                "insert into conversation_session(conversation_session_id, tenant_id, title, session_type, created_by_user_id) values (?, 'tenant_local', ?, 'INTERACTIVE', ?)",
                conversationSessionId,
                suffix,
                userId);
        jdbc.update(
                "insert into conversation_message(conversation_message_id, tenant_id, conversation_session_id, role, content, conversation_message_sequence, author_user_id) values (?, 'tenant_local', ?, 'user', ?, 1, ?)",
                conversationMessageId,
                conversationSessionId,
                suffix,
                userId);
        jdbc.update(
                """
                insert into agent_execution(
                    execution_id, tenant_id, conversation_session_id, triggering_conversation_message_id, status, phase,
                    execution_attempt_number, owner_user_id, request_id, queued_at,
                    request_type, request_idempotency_key, request_payload_hash)
                values (?, 'tenant_local', ?, ?, 'queued', 'QUEUED', 0, ?, ?, ?,
                        'message_submission', ?, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')
                """,
                executionId,
                conversationSessionId,
                conversationMessageId,
                userId,
                "request_" + suffix,
                Timestamp.from(queuedAt),
                "key_" + suffix);
        jdbc.update(
                "update conversation_message set execution_id = ? where conversation_message_id = ?",
                executionId,
                conversationMessageId);
    }

    private int runningFor(String userId) {
        return jdbc.queryForObject(
                "select count(*) from agent_execution where owner_user_id=? and status='running'",
                Integer.class,
                userId);
    }

    private int queuedFor(String userId) {
        return jdbc.queryForObject(
                "select count(*) from agent_execution where owner_user_id=? and status='queued'",
                Integer.class,
                userId);
    }

    static final class HoldingExecutionPort implements AgentExecutionPort {
        private final List<ClaimedAgentExecution> started = new ArrayList<>();

        @Override
        public void start(ClaimedAgentExecution run, Runnable onTerminated) {
            started.add(run);
        }
    }

    private record RuntimeContext(
            AnnotationConfigApplicationContext context,
            AgentExecutionClaimService claims,
            ExecutionWorkerCapacity capacity,
            ExecutionOwnerRegistry owners)
            implements AutoCloseable {
        @Override
        public void close() {
            context.close();
        }
    }
}
