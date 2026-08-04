package com.datagent.agentv2.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class Phase1MigrationTest {

    private static final List<String> TABLES =
            List.of(
                    "agent_execution",
                    "agent_execution_attempt",
                    "agent_execution_event",
                    "agent_execution_stream_checkpoint",
                    "conversation_message",
                    "conversation_session",
                    "model_invocation",
                    "notification_outbox_message",
                    "tenant",
                    "tenant_membership",
                    "tool_execution",
                    "user_account");
    private static final List<String> AUDITED_TABLES = TABLES;
    private static final List<String> APPLICATION_TABLES =
            List.of(
                    "agent_execution",
                    "agent_execution_attempt",
                    "agent_execution_event",
                    "agent_execution_stream_checkpoint",
                    "conversation_message",
                    "conversation_preference",
                    "conversation_session",
                    "conversation_session_user_state",
                    "model_invocation",
                    "notification_outbox_message",
                    "tenant",
                    "tenant_membership",
                    "tool_execution",
                    "user_account");
    private static final Map<String, String> BUSINESS_ID_COLUMNS =
            Map.ofEntries(
                    Map.entry("conversation_message", "conversation_message_id"),
                    Map.entry("model_invocation", "model_invocation_id"),
                    Map.entry("agent_execution", "execution_id"),
                    Map.entry("agent_execution_attempt", "execution_attempt_id"),
                    Map.entry("agent_execution_event", "execution_event_id"),
                    Map.entry(
                            "agent_execution_stream_checkpoint", "execution_stream_checkpoint_id"),
                    Map.entry("conversation_session", "conversation_session_id"),
                    Map.entry("tool_execution", "tool_execution_id"),
                    Map.entry("notification_outbox_message", "notification_message_id"),
                    Map.entry("tenant", "tenant_id"),
                    Map.entry("tenant_membership", "tenant_membership_id"),
                    Map.entry("user_account", "user_id"));

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private static Flyway flyway;

    @BeforeAll
    static void migrateEmptyDatabase() {
        POSTGRES.start();
        flyway =
                Flyway.configure()
                        .dataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword())
                        .locations("classpath:db/migration")
                        .load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(14);
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    void emptyDatabaseMigratesWithoutPostgresLoginSessions() throws SQLException {
        assertThat(applicationTables()).containsExactlyElementsOf(APPLICATION_TABLES);
    }

    @Test
    void businessTablesDeclareAuditColumnsAndSoftDeleteConsistency() throws SQLException {
        for (String table : AUDITED_TABLES) {
            assertThat(columns(table))
                    .as("audit columns for %s", table)
                    .contains("created_at", "updated_at", "deleted_at", "is_deleted");
            assertThat(columnMetadata(table, "created_at", "data_type"))
                    .as("local created_at type for %s", table)
                    .isEqualTo("timestamp without time zone");
            assertThat(columnMetadata(table, "updated_at", "data_type"))
                    .as("local updated_at type for %s", table)
                    .isEqualTo("timestamp without time zone");
            assertThat(columnMetadata(table, "deleted_at", "data_type"))
                    .as("local deleted_at type for %s", table)
                    .isEqualTo("timestamp without time zone");
            assertThat(checkConstraints(table))
                    .as("soft-delete constraint for %s", table)
                    .anySatisfy(
                            definition ->
                                    assertThat(definition)
                                            .contains("is_deleted")
                                            .contains("deleted_at"));
        }
    }

    @Test
    void everyBusinessTableUsesIdentityPrimaryKeyAndDedicatedBusinessId() throws SQLException {
        for (String table : TABLES) {
            String businessIdColumn = BUSINESS_ID_COLUMNS.get(table);
            assertThat(primaryKey(table))
                    .as("primary key for %s", table)
                    .isEqualTo("primary key (id)");
            assertThat(identityFlag(table, "id")).as("identity id for %s", table).isEqualTo("YES");
            assertThat(nullableFlag(table, businessIdColumn))
                    .as("non-null business id for %s", table)
                    .isEqualTo("NO");
            assertThat(uniqueConstraints(table))
                    .as("unique business id for %s", table)
                    .contains("unique (" + businessIdColumn + ")");
        }
    }

    @Test
    void tenantReferencesUseCompositeKeys() throws SQLException {
        assertForeignKey(
                "tenant_membership",
                "foreign key (tenant_id, user_id) references user_account(tenant_id, user_id)");
        assertForeignKey(
                "conversation_message",
                "foreign key (tenant_id, conversation_session_id) references conversation_session(tenant_id, conversation_session_id)");
        assertForeignKey(
                "agent_execution",
                "foreign key (tenant_id, conversation_session_id) references conversation_session(tenant_id, conversation_session_id)");
        assertForeignKey(
                "agent_execution_event",
                "foreign key (tenant_id, execution_id) references agent_execution(tenant_id, execution_id)");
        assertForeignKey(
                "agent_execution_stream_checkpoint",
                "foreign key (tenant_id, execution_id) references agent_execution(tenant_id, execution_id)");
    }

    @Test
    void executionAndSequenceIndexesProtectConversationInvariants() throws SQLException {
        assertThat(indexDefinition("uq_agent_execution_one_active"))
                .contains("(tenant_id, conversation_session_id)")
                .contains("where ((is_deleted = false)")
                .contains("status")
                .contains("running")
                .contains("waiting_for_user")
                .contains("waiting_confirmation");
        assertThat(uniqueConstraints("conversation_message"))
                .anySatisfy(
                        definition ->
                                assertThat(definition)
                                        .contains(
                                                "tenant_id",
                                                "conversation_session_id",
                                                "conversation_message_sequence"));
        assertThat(uniqueConstraints("agent_execution_event"))
                .anySatisfy(
                        definition ->
                                assertThat(definition)
                                        .contains(
                                                "tenant_id",
                                                "execution_id",
                                                "execution_event_sequence"));
        assertThat(primaryKey("agent_execution_stream_checkpoint")).isEqualTo("primary key (id)");
        assertThat(uniqueConstraints("agent_execution_stream_checkpoint"))
                .anySatisfy(
                        definition -> assertThat(definition).contains("tenant_id", "execution_id"));
    }

    @Test
    void aSecondMigrationExecutionIsIdempotent() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    private static List<String> applicationTables() throws SQLException {
        return query(
                """
                select table_name
                  from information_schema.tables
                 where table_schema = 'public'
                   and table_type = 'BASE TABLE'
                   and table_name <> 'flyway_schema_history'
                 order by table_name
                """);
    }

    private static List<String> columns(String table) throws SQLException {
        return query(
                """
                select column_name
                  from information_schema.columns
                 where table_schema = 'public' and table_name = ?
                 order by ordinal_position
                """,
                table);
    }

    private static List<String> checkConstraints(String table) throws SQLException {
        return constraints(table, "c");
    }

    private static List<String> foreignKeys(String table) throws SQLException {
        return constraints(table, "f");
    }

    private static List<String> uniqueConstraints(String table) throws SQLException {
        return constraints(table, "u");
    }

    private static String primaryKey(String table) throws SQLException {
        return constraints(table, "p").getFirst();
    }

    private static String identityFlag(String table, String column) throws SQLException {
        return columnMetadata(table, column, "is_identity");
    }

    private static String nullableFlag(String table, String column) throws SQLException {
        return columnMetadata(table, column, "is_nullable");
    }

    private static String columnMetadata(String table, String column, String metadataColumn)
            throws SQLException {
        return query(
                        "select "
                                + metadataColumn
                                + " from information_schema.columns "
                                + "where table_schema = 'public' and table_name = ? and column_name = ?",
                        table,
                        column)
                .getFirst();
    }

    private static List<String> constraints(String table, String type) throws SQLException {
        return query(
                """
                select lower(pg_get_constraintdef(c.oid))
                  from pg_constraint c
                  join pg_class t on t.oid = c.conrelid
                  join pg_namespace n on n.oid = t.relnamespace
                 where n.nspname = 'public' and t.relname = ? and c.contype = ?
                 order by c.conname
                """,
                table,
                type);
    }

    private static String indexDefinition(String indexName) throws SQLException {
        return query(
                        """
                        select lower(indexdef)
                          from pg_indexes
                         where schemaname = 'public' and indexname = ?
                        """,
                        indexName)
                .getFirst();
    }

    private static void assertForeignKey(String table, String expected) throws SQLException {
        assertThat(foreignKeys(table)).contains(expected);
    }

    private static List<String> query(String sql, String... arguments) throws SQLException {
        try (Connection connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setString(index + 1, arguments[index]);
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
