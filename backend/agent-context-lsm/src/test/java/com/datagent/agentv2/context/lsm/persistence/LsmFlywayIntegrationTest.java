package com.datagent.agentv2.context.lsm.persistence;

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

class LsmFlywayIntegrationTest {

    private static final String PRIMARY_HISTORY = "flyway_schema_history";
    private static final String LSM_HISTORY = "flyway_agent_context_lsm_schema_history";
    private static final String INITIAL_BASELINE_VERSION = "0";
    private static final List<String> LSM_TABLES =
            List.of(
                    "conversation_context_lsm_execution_policy",
                    "conversation_context_lsm_job",
                    "conversation_context_lsm_manifest",
                    "conversation_context_lsm_segment");

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @BeforeAll
    static void migrateDatabase() {
        POSTGRES.start();
        flyway("classpath:db/migration", PRIMARY_HISTORY).migrate();
        flyway("classpath:db/agent-context-lsm/migration", LSM_HISTORY).migrate();
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    void pluginMigrationUsesIndependentHistoryAndCreatesOnlyPluginTables() throws SQLException {
        assertThat(tables()).containsAll(LSM_TABLES).contains(PRIMARY_HISTORY, LSM_HISTORY);
        assertThat(migrationScripts(PRIMARY_HISTORY))
                .doesNotContain("V1__create_lsm_context_store.sql");
        assertThat(migrationScripts(LSM_HISTORY)).contains("V1__create_lsm_context_store.sql");
    }

    @Test
    void everyPluginColumnHasDocumentation() throws SQLException {
        for (String table : LSM_TABLES) {
            assertThat(undocumentedColumns(table)).as("column comments for %s", table).isEmpty();
        }
        assertThat(columnComment("conversation_context_lsm_segment", "segment_key"))
                .contains("确定性", "幂等键");
        assertThat(columnComment("conversation_context_lsm_manifest", "generation"))
                .contains("单调递增", "代次");
        assertThat(columnComment("conversation_context_lsm_job", "policy_snapshot"))
                .contains("冻结", "策略快照");
        assertThat(columnComment("conversation_context_lsm_execution_policy", "policy_snapshot"))
                .contains("首次进入", "不可变策略快照");
    }

    @Test
    void manifestHasOneRowPerTenantSession() throws SQLException {
        assertThat(
                        constraintDefinition(
                                "conversation_context_lsm_manifest",
                                "uk_context_lsm_manifest_session"))
                .isEqualTo("UNIQUE (tenant_id, conversation_session_id)");
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

    private static List<String> migrationScripts(String historyTable) throws SQLException {
        return query("select script from \"" + historyTable + "\" order by installed_rank");
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

    private static String constraintDefinition(String table, String constraint)
            throws SQLException {
        return query(
                        "select pg_get_constraintdef(oid) from pg_constraint where conrelid = ?::regclass and conname = ?",
                        table,
                        constraint)
                .getFirst();
    }

    private static String columnComment(String table, String column) throws SQLException {
        return query(
                        "select col_description(?::regclass, a.attnum) from pg_attribute a where a.attrelid = ?::regclass and a.attname = ?",
                        table,
                        table,
                        column)
                .getFirst();
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
