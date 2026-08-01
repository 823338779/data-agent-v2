package com.datagent.agentv2.platform.persistence;

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

class Phase1MigrationTest {

    private static final List<String> TABLES =
            List.of(
                    "agent_message",
                    "agent_run",
                    "agent_run_event",
                    "agent_run_stream",
                    "agent_session",
                    "login_session",
                    "notification_outbox",
                    "tenant",
                    "tenant_membership",
                    "user_account");
    private static final List<String> AUDITED_TABLES =
            TABLES.stream().filter(table -> !table.equals("notification_outbox")).toList();

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
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    void emptyDatabaseMigratesToExactlyTenApplicationTables() throws SQLException {
        assertThat(applicationTables()).containsExactlyElementsOf(TABLES);
    }

    @Test
    void businessTablesDeclareAuditColumnsAndSoftDeleteConsistency() throws SQLException {
        for (String table : AUDITED_TABLES) {
            assertThat(columns(table))
                    .as("audit columns for %s", table)
                    .contains("created_at", "updated_at", "deleted_at", "is_deleted");
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
    void tenantReferencesUseCompositeKeys() throws SQLException {
        assertForeignKey(
                "tenant_membership",
                "foreign key (tenant_id, user_id) references user_account(tenant_id, id)");
        assertForeignKey(
                "login_session",
                "foreign key (tenant_id, user_id) references user_account(tenant_id, id)");
        assertForeignKey(
                "agent_message",
                "foreign key (tenant_id, session_id) references agent_session(tenant_id, id)");
        assertForeignKey(
                "agent_run",
                "foreign key (tenant_id, session_id) references agent_session(tenant_id, id)");
        assertForeignKey(
                "agent_run_event",
                "foreign key (tenant_id, run_id) references agent_run(tenant_id, id)");
        assertForeignKey(
                "agent_run_stream",
                "foreign key (tenant_id, run_id) references agent_run(tenant_id, id)");
    }

    @Test
    void runAndSequenceIndexesProtectConversationInvariants() throws SQLException {
        assertThat(indexDefinition("uq_agent_run_one_active"))
                .contains("(tenant_id, session_id)")
                .contains("where ((is_deleted = false)")
                .contains("status")
                .contains("running")
                .contains("waiting_for_user")
                .contains("waiting_confirmation");
        assertThat(uniqueConstraints("agent_message"))
                .anySatisfy(
                        definition ->
                                assertThat(definition)
                                        .contains("tenant_id", "session_id", "sequence"));
        assertThat(uniqueConstraints("agent_run_event"))
                .anySatisfy(
                        definition ->
                                assertThat(definition).contains("tenant_id", "run_id", "sequence"));
        assertThat(primaryKey("agent_run_stream")).contains("(run_id)");
        assertThat(uniqueConstraints("agent_run_stream"))
                .anySatisfy(definition -> assertThat(definition).contains("tenant_id", "run_id"));
    }

    @Test
    void aSecondMigrationRunIsIdempotent() {
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
