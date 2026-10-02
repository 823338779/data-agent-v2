package com.datagent.agentv2.context.hierarchical.persistence;

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

class HierarchicalFlywayIntegrationTest {

    private static final String PRIMARY_MIGRATION_LOCATION = "classpath:db/migration";
    private static final String HIERARCHICAL_MIGRATION_LOCATION =
            "classpath:db/agent-context-hierarchical/migration";
    private static final String PRIMARY_HISTORY = "flyway_schema_history";
    private static final String HIERARCHICAL_HISTORY =
            "flyway_agent_context_hierarchical_schema_history";
    private static final String INITIAL_BASELINE_VERSION = "0";
    private static final String HIERARCHICAL_TABLE_PREFIX = "conversation_context_hierarchical_%";
    private static final String INITIAL_MIGRATION_SCRIPT =
            "V1__create_hierarchical_context_store.sql";
    private static final String REMOVE_SINGLE_VALUE_FIELDS_SCRIPT =
            "V2__remove_single_value_summary_versions.sql";
    private static final String RESET_LEGACY_CONTEXT_SCRIPT =
            "V3__reset_legacy_turn_completion_context.sql";
    private static final List<String> HIERARCHICAL_TABLES =
            List.of(
                    "conversation_context_hierarchical_checkpoint",
                    "conversation_context_hierarchical_cold");

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @BeforeAll
    static void migrateDatabase() {
        POSTGRES.start();
        flyway(PRIMARY_MIGRATION_LOCATION, PRIMARY_HISTORY).migrate();
        flyway(HIERARCHICAL_MIGRATION_LOCATION, HIERARCHICAL_HISTORY).migrate();
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    void pluginMigrationUsesIndependentHistoryAndCreatesOnlyRollingColdStorage()
            throws SQLException {
        assertThat(hierarchicalTables()).containsExactlyElementsOf(HIERARCHICAL_TABLES);
        assertThat(tables()).contains(PRIMARY_HISTORY, HIERARCHICAL_HISTORY);
        assertThat(migrationScripts(PRIMARY_HISTORY)).doesNotContain(INITIAL_MIGRATION_SCRIPT);
        assertThat(migrationScripts(HIERARCHICAL_HISTORY))
                .contains(
                        INITIAL_MIGRATION_SCRIPT,
                        REMOVE_SINGLE_VALUE_FIELDS_SCRIPT,
                        RESET_LEGACY_CONTEXT_SCRIPT);
    }

    @Test
    void incompatibleSchemaStartsFromOneMigration() throws SQLException {
        assertThat(migrationScripts(HIERARCHICAL_HISTORY))
                .containsExactly(
                        "<< Flyway Baseline >>",
                        INITIAL_MIGRATION_SCRIPT,
                        REMOVE_SINGLE_VALUE_FIELDS_SCRIPT,
                        RESET_LEGACY_CONTEXT_SCRIPT);
    }

    @Test
    void pluginTablesReferenceSessionsThroughTenantCompositeKeys() throws SQLException {
        for (String table : HIERARCHICAL_TABLES) {
            assertThat(foreignKeyDefinitions(table))
                    .contains(
                            "FOREIGN KEY (tenant_id, conversation_session_id) REFERENCES conversation_session(tenant_id, conversation_session_id)");
        }
    }

    @Test
    void pluginTablesEnforceIdentityPointerAndLifecycleConstraints() throws SQLException {
        assertThat(
                        constraintDefinition(
                                "conversation_context_hierarchical_checkpoint",
                                "uk_hierarchical_checkpoint_session"))
                .isEqualTo("UNIQUE (tenant_id, conversation_session_id)");
        assertThat(hierarchicalTables())
                .contains("conversation_context_hierarchical_cold")
                .doesNotContain("conversation_context_hierarchical_summary_node");
        assertThat(checkDefinitions("conversation_context_hierarchical_checkpoint"))
                .anySatisfy(
                        definition ->
                                assertThat(definition)
                                        .contains(
                                                "evaluated_up_to_conversation_message_sequence >= 0",
                                                "strategy_generation >= 0"));
        assertThat(checkDefinitions("conversation_context_hierarchical_cold"))
                .anySatisfy(
                        definition ->
                                assertThat(definition)
                                        .contains(
                                                "persisted_token_count > 0",
                                                "rendered_token_count > 0",
                                                "rendered_token_count <= target_rendered_tokens"));
        assertThat(columnNames("conversation_context_hierarchical_cold"))
                .doesNotContain("summary_schema_version", "summary_prompt_version");
    }

    private static Flyway flyway(String location, String historyTable) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(location)
                .table(historyTable)
                .baselineOnMigrate(true)
                .baselineVersion(INITIAL_BASELINE_VERSION)
                .load();
    }

    private static List<String> tables() throws SQLException {
        return query(
                "select table_name from information_schema.tables where table_schema = 'public'");
    }

    private static List<String> hierarchicalTables() throws SQLException {
        return query(
                "select table_name from information_schema.tables where table_schema = 'public' and table_name like ? order by table_name",
                HIERARCHICAL_TABLE_PREFIX);
    }

    private static List<String> migrationScripts(String historyTable) throws SQLException {
        return query("select script from \"" + historyTable + "\" order by installed_rank");
    }

    private static List<String> foreignKeyDefinitions(String table) throws SQLException {
        return query(
                "select pg_get_constraintdef(oid) from pg_constraint where conrelid = ?::regclass and contype = 'f'",
                table);
    }

    private static List<String> checkDefinitions(String table) throws SQLException {
        return query(
                "select pg_get_constraintdef(oid) from pg_constraint where conrelid = ?::regclass and contype = 'c'",
                table);
    }

    private static List<String> columnNames(String table) throws SQLException {
        return query(
                "select column_name from information_schema.columns where table_schema = 'public' and table_name = ? order by ordinal_position",
                table);
    }

    private static String constraintDefinition(String table, String constraint)
            throws SQLException {
        return query(
                        "select pg_get_constraintdef(oid) from pg_constraint where conrelid = ?::regclass and conname = ?",
                        table,
                        constraint)
                .getFirst();
    }

    private static List<String> undocumentedColumns(String table) throws SQLException {
        return query(
                """
                select a.attname
                  from pg_attribute a
                  join pg_class c on c.oid = a.attrelid
                  join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = 'public' and c.relname = ?
                   and a.attnum > 0 and not a.attisdropped
                   and col_description(c.oid, a.attnum) is null
                """,
                table);
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
