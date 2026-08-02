package com.datagent.agentv2.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class ConversationExecutionSchemaMigrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @BeforeAll
    static void migrateLegacyData() throws SQLException {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("2"))
                .load()
                .migrate();

        execute(
                """
                insert into tenant(id, name) values ('tenant_legacy', 'Legacy');
                insert into user_account(id, tenant_id, username, password_hash)
                    values ('user_legacy', 'tenant_legacy', 'legacy', 'hash');
                insert into agent_session(id, tenant_id, title, session_type, created_by)
                    values ('session_legacy', 'tenant_legacy', 'Legacy', 'INTERACTIVE', 'user_legacy');
                insert into agent_message(id, tenant_id, session_id, role, content, sequence, author_user_id)
                    values ('message_legacy', 'tenant_legacy', 'session_legacy', 'user', 'hello', 1, 'user_legacy');
                insert into agent_run(
                    id, tenant_id, session_id, triggering_message_id, status, phase,
                    execution_attempt, owner_instance_id, request_id, started_at, hard_deadline_at)
                    values (
                    'execution_legacy', 'tenant_legacy', 'session_legacy', 'message_legacy', 'running',
                    'THINKING', 1, 'instance_legacy', 'request_legacy',
                    '2026-08-01T00:00:00Z', '2026-08-01T00:10:00Z');
                """);

        int migratedToVersionSeven =
                Flyway.configure()
                        .dataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword())
                        .locations("classpath:db/migration")
                        .target(MigrationVersion.fromVersion("7"))
                        .load()
                        .migrate()
                        .migrationsExecuted;
        assertThat(migratedToVersionSeven).isEqualTo(5);

        // 模拟真实库中执行已被领取、但首个模型调用尚未开始的合法检查点。
        execute(
                """
                insert into agent_run_stream(
                    stream_id, run_id, tenant_id, execution_attempt, phase,
                    model_turn_sequence, stream_sequence)
                    values (
                    'stream_legacy', 'execution_legacy', 'tenant_legacy', 1,
                    'RECEIVED', 0, 0)
                    on conflict (tenant_id, run_id) do update
                       set execution_attempt = excluded.execution_attempt,
                           model_turn_sequence = excluded.model_turn_sequence,
                           stream_sequence = excluded.stream_sequence,
                           phase = excluded.phase
                """);

        int migratedToLatest =
                Flyway.configure()
                        .dataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword())
                        .locations("classpath:db/migration")
                        .load()
                        .migrate()
                        .migrationsExecuted;
        assertThat(migratedToLatest).isEqualTo(3);
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    void v1AndV2ExecutionDataReceivesOwnerLeaseAndBudgetDefaults() throws SQLException {
        assertThat(
                        row(
                                """
                                select owner_user_id, queued_at is not null, lease_expires_at is not null,
                                       heartbeat_at is not null, active_segment_started_at is not null,
                                       execution_budget_ms, active_execution_ms, request_type is null
                                  from agent_execution where execution_id = 'execution_legacy'
                                """))
                .containsExactly("user_legacy", "t", "t", "t", "t", "600000", "0", "t");
    }

    @Test
    void queuedExecutionAllowsAttemptZeroAndNullableOwnershipFields() throws SQLException {
        execute(
                """
                insert into conversation_session(conversation_session_id, tenant_id, title, session_type, created_by_user_id)
                    values ('session_queued', 'tenant_legacy', 'Queued', 'INTERACTIVE', 'user_legacy');
                insert into conversation_message(conversation_message_id, tenant_id, conversation_session_id, role, content, conversation_message_sequence, author_user_id)
                    values ('message_queued', 'tenant_legacy', 'session_queued', 'user', 'queued', 1, 'user_legacy');
                insert into agent_execution(
                    execution_id, tenant_id, conversation_session_id, triggering_conversation_message_id, status, phase,
                    execution_attempt_number, owner_user_id, owner_instance_id, request_id, queued_at,
                    started_at, hard_deadline_at, lease_expires_at, heartbeat_at,
                    active_segment_started_at, request_type, request_idempotency_key,
                    request_payload_hash)
                    values (
                    'execution_queued', 'tenant_legacy', 'session_queued', 'message_queued', 'queued',
                    'QUEUED', 0, 'user_legacy', null, 'request_queued', current_timestamp,
                    null, null, null, null, null, 'message_submission', 'key-1',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa');
                """);

        assertThat(
                        row(
                                """
                                select execution_attempt_number, owner_instance_id is null,
                                       hard_deadline_at is null, lease_expires_at is null
                                  from agent_execution where execution_id = 'execution_queued'
                                """))
                .containsExactly("0", "t", "t", "t");
    }

    @Test
    void executionChecksRejectInvalidStateAndPartialIdempotencyData() {
        assertThatThrownBy(
                        () ->
                                execute(
                                        """
                                        update agent_execution set status = 'unknown' where execution_id = 'execution_legacy'
                                        """))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(
                        () ->
                                execute(
                                        """
                                        update agent_execution set request_type = 'message_submission'
                                         where execution_id = 'execution_legacy'
                                        """))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void partialIndexesProtectIdempotencyQueueCapacityAndRecovery() throws SQLException {
        assertThat(indexDefinition("uq_agent_execution_request_idempotency"))
                .contains("unique")
                .contains(
                        "tenant_id, conversation_session_id, request_type, request_idempotency_key")
                .contains("where (request_idempotency_key is not null)");
        assertThat(indexDefinition("uq_agent_execution_one_active"))
                .contains("queued", "running", "waiting_for_user", "waiting_confirmation");
        assertThat(indexDefinition("ix_agent_execution_queued"))
                .contains("queued_at", "where", "status", "queued");
        assertThat(indexDefinition("ix_agent_execution_user_running"))
                .contains("tenant_id, owner_user_id", "status", "running");
        assertThat(indexDefinition("ix_agent_execution_expired_lease"))
                .contains("lease_expires_at", "status", "running");
    }

    @Test
    void streamAndAttemptUseIdPrimaryKeysAndKeepBusinessUniqueness() throws SQLException {
        assertThat(primaryKey("agent_execution_stream_checkpoint")).isEqualTo("primary key (id)");
        assertThat(primaryKey("agent_execution_attempt")).isEqualTo("primary key (id)");
        assertThat(constraintDefinition("uq_agent_execution_attempt_execution"))
                .contains("unique", "tenant_id", "execution_id", "execution_attempt_number");
    }

    @Test
    void claimedExecutionBeforeFirstModelInvocationKeepsItsStreamCoordinate() throws SQLException {
        assertThat(
                        row(
                                """
                                select execution_attempt_number, model_invocation_sequence,
                                       output_delta_sequence, phase
                                  from agent_execution_stream_checkpoint
                                 where execution_id = 'execution_legacy'
                                """))
                .containsExactly("1", "0", "0", "RECEIVED");
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> row(String sql) throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            List<String> values = new ArrayList<>();
            for (int index = 1; index <= resultSet.getMetaData().getColumnCount(); index++) {
                values.add(resultSet.getString(index));
            }
            return values;
        }
    }

    private static String primaryKey(String table) throws SQLException {
        return query(
                        """
                        select lower(pg_get_constraintdef(c.oid))
                          from pg_constraint c
                          join pg_class t on t.oid = c.conrelid
                         where t.relname = ? and c.contype = 'p'
                        """,
                        table)
                .getFirst();
    }

    private static String indexDefinition(String index) throws SQLException {
        return query(
                        """
                        select lower(indexdef) from pg_indexes
                         where schemaname = 'public' and indexname = ?
                        """,
                        index)
                .getFirst();
    }

    private static String constraintDefinition(String constraint) throws SQLException {
        return query(
                        """
                        select lower(pg_get_constraintdef(c.oid))
                          from pg_constraint c
                         where c.conname = ?
                        """,
                        constraint)
                .getFirst();
    }

    private static List<String> query(String sql, String argument) throws SQLException {
        try (Connection connection = connection();
                var statement = connection.prepareStatement(sql)) {
            statement.setString(1, argument);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (resultSet.next()) {
                    values.add(resultSet.getString(1));
                }
                return values;
            }
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
