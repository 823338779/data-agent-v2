package com.datagent.agentv2.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class ConversationExecutionSchemaMigrationTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @BeforeAll
    static void migrateFreshDatabase() {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    void freshSchemaContainsOnlySingleInstanceExecutionFacts() throws SQLException {
        assertThat(tableNames())
                .contains(
                        "agent_execution",
                        "agent_execution_event",
                        "agent_execution_stream_checkpoint")
                .doesNotContain(
                        "agent_execution_attempt",
                        "notification_outbox_message",
                        "model_invocation",
                        "tool_execution");

        assertThat(columnNames("agent_execution"))
                .contains("execution_id", "conversation_session_id", "status", "hard_deadline_at")
                .doesNotContain(
                        "request_type",
                        "execution_attempt_number",
                        "owner_user_id",
                        "owner_instance_id",
                        "request_id",
                        "queued_at",
                        "lease_expires_at",
                        "heartbeat_at",
                        "cancellation_requested_at",
                        "retry_of_execution_id",
                        "recovery_attempt_count",
                        "execution_budget_ms",
                        "active_execution_ms",
                        "active_segment_started_at",
                        "phase",
                        "started_at",
                        "completed_at",
                        "failed_at");

        assertThat(columnNames("agent_execution_event")).doesNotContain("payload");
        assertThat(columnNames("agent_execution_stream_checkpoint"))
                .contains(
                        "execution_id",
                        "model_invocation_sequence",
                        "phase",
                        "output_delta_sequence",
                        "answer")
                .doesNotContain(
                        "execution_stream_checkpoint_id",
                        "execution_attempt_number",
                        "checkpointed_at");
    }

    @Test
    void executionStatusConstraintExposesOnlyLocalLifecycleStates() throws SQLException {
        assertThat(constraintDefinition("ck_agent_execution_status"))
                .contains("running", "done", "cancelled", "failed")
                .doesNotContain("queued", "lost", "waiting_for_user", "waiting_confirmation");
        assertThat(constraintDefinition("uq_agent_execution_request_idempotency"))
                .contains("tenant_id", "conversation_session_id", "request_idempotency_key")
                .doesNotContain("request_type");
        assertThat(indexDefinition("uq_agent_execution_one_active"))
                .contains("status", "running")
                .doesNotContain("queued", "waiting_for_user", "waiting_confirmation");
    }

    private static List<String> tableNames() throws SQLException {
        return query(
                """
                select lower(table_name) from information_schema.tables
                 where table_schema = 'public' order by table_name
                """,
                null);
    }

    private static List<String> columnNames(String table) throws SQLException {
        return query(
                """
                select lower(column_name) from information_schema.columns
                 where table_schema = 'public' and table_name = ? order by ordinal_position
                """,
                table);
    }

    private static String constraintDefinition(String constraint) throws SQLException {
        return query(
                        """
                        select lower(pg_get_constraintdef(c.oid))
                          from pg_constraint c where c.conname = ?
                        """,
                        constraint)
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

    private static List<String> query(String sql, String argument) throws SQLException {
        try (Connection connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                var statement = connection.prepareStatement(sql)) {
            if (argument != null) {
                statement.setString(1, argument);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (resultSet.next()) {
                    values.add(resultSet.getString(1));
                }
                return values;
            }
        }
    }
}
