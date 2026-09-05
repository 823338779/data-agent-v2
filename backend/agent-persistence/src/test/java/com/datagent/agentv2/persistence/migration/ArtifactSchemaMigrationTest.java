package com.datagent.agentv2.persistence.migration;

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

class ArtifactSchemaMigrationTest {

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
    void createsArtifactTablesWithRequiredBusinessColumns() throws SQLException {
        assertThat(tableNames())
                .contains(
                        "artifact_build_job",
                        "artifact_object",
                        "conversation_message_artifact",
                        "message_ui_block");
        assertThat(columnNames("artifact_build_job"))
                .contains(
                        "artifact_build_job_id",
                        "conversation_session_id",
                        "execution_id",
                        "conversation_message_id",
                        "specification",
                        "status");
        assertThat(columnNames("artifact_object"))
                .contains(
                        "artifact_id", "source_artifact_id", "storage_key", "size_bytes", "sha256");
        assertThat(columnNames("message_ui_block"))
                .contains("ui_block_id", "type", "schema_version", "payload", "display_order");
    }

    @Test
    void constrainsArtifactLifecycleIntegrityAndLookupIndexes() throws SQLException {
        assertThat(constraintDefinition("ck_artifact_build_job_status"))
                .contains(
                        "prepared",
                        "running",
                        "rendering",
                        "verifying",
                        "succeeded",
                        "failed",
                        "cancelled",
                        "timed_out");
        assertThat(constraintDefinition("ck_artifact_object_sha256")).contains("sha256", "64");
        assertThat(constraintDefinition("ck_artifact_object_size")).contains("size_bytes", "> 0");
        assertThat(constraintDefinition("uq_conversation_message_artifact_relation"))
                .contains("tenant_id", "conversation_message_id", "artifact_id");
        assertThat(indexDefinition("ix_artifact_object_session"))
                .contains("tenant_id", "conversation_session_id");
        assertThat(indexDefinition("ix_message_ui_block_message"))
                .contains("tenant_id", "conversation_message_id", "display_order");
    }

    @Test
    void documentsEveryArtifactTableColumnInChinese() throws SQLException {
        for (String table :
                List.of(
                        "artifact_build_job",
                        "artifact_object",
                        "conversation_message_artifact",
                        "message_ui_block")) {
            assertThat(columnComments(table))
                    .as("all columns in %s have Chinese comments", table)
                    .hasSize(columnNames(table).size())
                    .allMatch(
                            comment -> comment != null && comment.matches(".*[\\u4e00-\\u9fff].*"));
        }
    }

    private static List<String> tableNames() throws SQLException {
        return query(
                "select lower(table_name) from information_schema.tables "
                        + "where table_schema = 'public' order by table_name",
                null);
    }

    private static List<String> columnNames(String table) throws SQLException {
        return query(
                "select lower(column_name) from information_schema.columns "
                        + "where table_schema = 'public' and table_name = ? order by ordinal_position",
                table);
    }

    private static List<String> columnComments(String table) throws SQLException {
        return query(
                """
                select col_description(c.oid, a.attnum)
                  from pg_class c
                  join pg_namespace n on n.oid = c.relnamespace
                  join pg_attribute a on a.attrelid = c.oid
                 where n.nspname = 'public' and c.relname = ?
                   and a.attnum > 0 and not a.attisdropped
                 order by a.attnum
                """,
                table);
    }

    private static String constraintDefinition(String constraint) throws SQLException {
        return query(
                        "select lower(pg_get_constraintdef(c.oid)) "
                                + "from pg_constraint c where c.conname = ?",
                        constraint)
                .getFirst();
    }

    private static String indexDefinition(String index) throws SQLException {
        return query(
                        "select lower(indexdef) from pg_indexes "
                                + "where schemaname = 'public' and indexname = ?",
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
