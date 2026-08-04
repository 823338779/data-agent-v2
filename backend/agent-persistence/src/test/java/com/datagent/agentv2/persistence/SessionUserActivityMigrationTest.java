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

        // 迁移前数据覆盖两租户、同租户两用户和创建时间相同的确定性排序边界。
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
        assertThat(migrated).isEqualTo(7);
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    void existingSessionsReceiveDeterministicPerUserOrdering() throws SQLException {
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
                                select user_id, conversation_session_id, sort_order
                                  from conversation_session_user_state
                                 where tenant_id = 'tenant_alpha'
                                 order by user_id, sort_order
                                """))
                .containsExactly(
                        List.of("user_alpha_a", "session_alpha_b", "0"),
                        List.of("user_alpha_a", "session_alpha_a", "1"),
                        List.of("user_alpha_b", "session_alpha_b", "0"),
                        List.of("user_alpha_b", "session_alpha_a", "1"));
        assertThat(
                        row(
                                """
                                select last_activity_at = updated_at, last_notifiable_event_sequence
                                  from conversation_session where conversation_session_id = 'session_alpha_a'
                                """))
                .containsExactly("t", "0");
    }

    @Test
    void userOrdersAreIndependentAndTenantBoundariesAreEnforced() throws SQLException {
        // 用户 A 的重排不得改变同租户用户 B 的顺序。
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(
                        """
                        update conversation_session_user_state
                           set sort_order = sort_order + 100
                         where tenant_id = 'tenant_alpha' and user_id = 'user_alpha_a';
                        update conversation_session_user_state
                           set sort_order = case conversation_session_id
                               when 'session_alpha_a' then 0 else 1 end
                         where tenant_id = 'tenant_alpha' and user_id = 'user_alpha_a'
                        """);
            }
            assertThat(
                            rows(
                                    connection,
                                    """
                                    select conversation_session_id, sort_order
                                      from conversation_session_user_state
                                     where tenant_id = 'tenant_alpha' and user_id = 'user_alpha_b'
                                     order by sort_order
                                    """))
                    .containsExactly(
                            List.of("session_alpha_b", "0"), List.of("session_alpha_a", "1"));
            connection.rollback();
        }

        // 复合外键拒绝把另一租户的用户和会话拼成状态记录。
        assertThatThrownBy(
                        () ->
                                execute(
                                        """
                                        insert into conversation_session_user_state(
                                            tenant_id, user_id, conversation_session_id, sort_order)
                                        values ('tenant_alpha', 'user_alpha_a', 'session_beta', 9)
                                        """))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void activityTablesExposeReconciliationIndexesAndOrderingVersions() throws SQLException {
        assertThat(row("select count(*) from conversation_preference")).containsExactly("3");
        assertThat(indexDefinition("ix_conversation_session_user_order"))
                .contains("tenant_id", "user_id", "sort_order");
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
