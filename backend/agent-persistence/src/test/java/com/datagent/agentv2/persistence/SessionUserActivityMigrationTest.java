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

class SessionUserActivityMigrationTest {

    private static final String POSTGRES_IMAGE = "postgres:17-alpine";
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE));

    @BeforeAll
    static void migrateExistingSessions() throws SQLException {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("9"))
                .load()
                .migrate();

        // 迁移前数据覆盖两租户和同租户两用户，用于验证每用户已读状态回填。
        execute(
                """
                insert into tenant(tenant_id, name) values
                    ('tenant_alpha', 'Alpha'), ('tenant_beta', 'Beta');
                insert into user_account(user_id, tenant_id, username, password_hash) values
                    ('user_alpha_a', 'tenant_alpha', 'alpha-a', 'hash'),
                    ('user_alpha_b', 'tenant_alpha', 'alpha-b', 'hash'),
                    ('user_beta', 'tenant_beta', 'beta', 'hash');
                insert into conversation_session(
                    conversation_session_id, tenant_id, title, session_type, created_by_user_id,
                    created_at, updated_at) values
                    ('session_alpha_a', 'tenant_alpha', 'A', 'INTERACTIVE', 'user_alpha_a',
                     '2026-08-01T08:00:00Z', '2026-08-01T08:01:00Z'),
                    ('session_alpha_b', 'tenant_alpha', 'B', 'INTERACTIVE', 'user_alpha_b',
                     '2026-08-01T08:00:00Z', '2026-08-01T08:02:00Z'),
                    ('session_beta', 'tenant_beta', 'Beta', 'INTERACTIVE', 'user_beta',
                     '2026-08-01T08:00:00Z', '2026-08-01T08:03:00Z');
                """);

        int migrated =
                Flyway.configure()
                        .dataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword())
                        .locations("classpath:db/migration")
                        .load()
                        .migrate()
                        .migrationsExecuted;
        assertThat(migrated).isEqualTo(9);
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    void existingSessionsReceivePerUserReadState() throws SQLException {
        assertThat(
                        row(
                                """
                                select count(id), count(distinct id), min(id) > 0
                                  from conversation_session_user_state
                                """))
                .containsExactly("5", "5", "t");
        assertThat(
                        rows(
                                """
                                select user_id, conversation_session_id
                                  from conversation_session_user_state
                                 where tenant_id = 'tenant_alpha'
                                 order by user_id, conversation_session_id
                                """))
                .containsExactly(
                        List.of("user_alpha_a", "session_alpha_a"),
                        List.of("user_alpha_a", "session_alpha_b"),
                        List.of("user_alpha_b", "session_alpha_a"),
                        List.of("user_alpha_b", "session_alpha_b"));
        assertThat(
                        row(
                                """
                                select last_activity_at = updated_at, last_notifiable_event_sequence
                                  from conversation_session where conversation_session_id = 'session_alpha_a'
                                """))
                .containsExactly("t", "0");
    }

    @Test
    void userReadStatesRemainTenantBound() throws SQLException {
        // 复合外键拒绝把另一租户的用户和会话拼成状态记录。
        assertThatThrownBy(
                        () ->
                                execute(
                                        """
                                        insert into conversation_session_user_state(
                                            tenant_id, user_id, conversation_session_id)
                                        values ('tenant_alpha', 'user_alpha_a', 'session_beta')
                                        """))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void activityTablesExposeOnlyReadAndReconciliationState() throws SQLException {
        assertThat(
                        row(
                                "select count(*) from information_schema.tables where table_schema = 'public' and table_name = 'conversation_preference'"))
                .containsExactly("0");
        assertThat(
                        row(
                                "select count(*) from information_schema.columns where table_schema = 'public' and table_name = 'conversation_session_user_state' and column_name = 'sort_order'"))
                .containsExactly("0");
        assertThat(indexDefinition("ix_conversation_session_activity_reconciliation"))
                .contains("tenant_id", "last_activity_at");
        assertThat(indexDefinition("ix_conversation_session_user_unread"))
                .contains("tenant_id", "user_id", "last_read_event_sequence");
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> row(String sql) throws SQLException {
        return rows(sql).getFirst();
    }

    private static List<List<String>> rows(String sql) throws SQLException {
        try (Connection connection = connection()) {
            return rows(connection, sql);
        }
    }

    private static List<List<String>> rows(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            List<List<String>> rows = new ArrayList<>();
            while (resultSet.next()) {
                List<String> values = new ArrayList<>();
                for (int index = 1; index <= resultSet.getMetaData().getColumnCount(); index++) {
                    values.add(resultSet.getString(index));
                }
                rows.add(values);
            }
            return rows;
        }
    }

    private static String indexDefinition(String index) throws SQLException {
        return row("""
                        select lower(indexdef) from pg_indexes
                         where schemaname = 'public' and indexname = '%s'
                        """
                        .formatted(index))
                .getFirst();
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
