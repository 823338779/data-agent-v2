package com.datagent.agentv2.context.hierarchical.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class HierarchicalDerivedDataResetMigrationTest {
    private static final String PRIMARY_MIGRATION_LOCATION = "classpath:db/migration";
    private static final String HIERARCHICAL_MIGRATION_LOCATION =
            "classpath:db/agent-context-hierarchical/migration";
    private static final String PRIMARY_HISTORY = "flyway_schema_history";
    private static final String HIERARCHICAL_HISTORY =
            "flyway_agent_context_hierarchical_schema_history";
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @BeforeAll
    static void startDatabase() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    void v3ClearsOnlyLegacyHierarchicalDerivedData() throws SQLException {
        flyway(PRIMARY_MIGRATION_LOCATION, PRIMARY_HISTORY, null).migrate();
        flyway(
                        HIERARCHICAL_MIGRATION_LOCATION,
                        HIERARCHICAL_HISTORY,
                        MigrationVersion.fromVersion("2"))
                .migrate();
        insertTenantSessionColdAndCheckpoint();

        assertThat(count("conversation_context_hierarchical_checkpoint")).isEqualTo(1);
        assertThat(count("conversation_context_hierarchical_cold")).isEqualTo(1);
        assertThat(count("conversation_session")).isEqualTo(1);

        flyway(HIERARCHICAL_MIGRATION_LOCATION, HIERARCHICAL_HISTORY, null).migrate();

        assertThat(count("conversation_context_hierarchical_checkpoint")).isZero();
        assertThat(count("conversation_context_hierarchical_cold")).isZero();
        assertThat(count("conversation_session")).isEqualTo(1);
    }

    private static Flyway flyway(String location, String historyTable, MigrationVersion target) {
        var configuration =
                Flyway.configure()
                        .dataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword())
                        .locations(location)
                        .table(historyTable)
                        .baselineOnMigrate(true)
                        .baselineVersion("0");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static void insertTenantSessionColdAndCheckpoint() throws SQLException {
        execute(
                """
                insert into tenant (tenant_id, name) values ('tenant_reset', 'Reset Test');
                insert into conversation_session (
                    conversation_session_id, tenant_id, title
                ) values ('session_reset', 'tenant_reset', 'Reset Test');
                insert into conversation_context_hierarchical_cold (
                    hierarchical_cold_id, tenant_id, conversation_session_id,
                    start_turn_sequence, end_turn_sequence,
                    start_message_sequence, end_message_sequence,
                    summary_document, persisted_token_count, rendered_token_count,
                    target_rendered_tokens, model_provider, model_name,
                    source_content_sha256, created_at, created_by, updated_at, updated_by
                ) values (
                    'cold_reset', 'tenant_reset', 'session_reset',
                    1, 1, 1, 2,
                    '{}'::jsonb, 1, 1, 1, 'test', 'test',
                    repeat('0', 64), localtimestamp, 'test', localtimestamp, 'test'
                );
                insert into conversation_context_hierarchical_checkpoint (
                    hierarchical_checkpoint_id, tenant_id, conversation_session_id,
                    active_cold_id, evaluated_up_to_conversation_message_sequence,
                    strategy_generation, created_at, created_by, updated_at, updated_by
                ) values (
                    'checkpoint_reset', 'tenant_reset', 'session_reset',
                    'cold_reset', 2, 1, localtimestamp, 'test', localtimestamp, 'test'
                );
                """);
    }

    private static int count(String table) throws SQLException {
        try (Connection connection = connection();
                var statement = connection.createStatement();
                var result = statement.executeQuery("select count(*) from " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connection();
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
