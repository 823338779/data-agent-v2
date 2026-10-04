package com.datagent.agentv2.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 在旧版本数据库上验证工作区解耦，不以新实体掩盖历史迁移问题。 */
class GitBranchWorkspaceMigrationTest {
    private static final String IMAGE = "postgres:17-alpine";
    private static final String MIGRATIONS = "classpath:db/migration";
    private static final String OLD_VERSION = "12";
    private static final String LEGACY =
            """
            insert into git_workspace(workspace_id,conversation_session_id,repository_id,status)
            values ('legacy','s1','metrics_store','READY')
            """;
    private static final String CANDIDATE =
            """
            insert into git_change(change_id,workspace_id,conversation_session_id,repository_id,branch,status)
            values ('old-candidate','legacy','s1','metrics_store','main','PENDING_CONFIRMATION')
            """;
    private static final String WORKSPACES =
            """
            insert into git_workspace(workspace_id,conversation_session_id,repository_id,status,project_id,branch_id,git_ref)
            values ('wa','s1','metrics_store','READY','p','branch-a','refs/heads/a'),
                   ('wb','s1','metrics_store','READY','p','branch-b','refs/heads/b'),
                   ('wa2','s2','metrics_store','READY','p','branch-a','refs/heads/a')
            """;
    private static final String ASSOCIATIONS =
            """
            insert into git_session_workspace(conversation_session_id,workspace_id)
            values ('s1','wa'),('s1','wb'),('s2','wa'),('s2','wa2')
            """;
    private static final String LEGACY_QUERY =
            "select legacy_read_only,git_ref from git_workspace where workspace_id='legacy'";
    private static final String LEGACY_ASSOCIATION =
            "select count(*) from git_session_workspace where conversation_session_id='s1' and workspace_id='legacy'";
    private static final String OLD_CANDIDATE =
            "select branch from git_change where change_id='old-candidate'";
    private static final String DETACH =
            "delete from git_session_workspace where conversation_session_id='s1'";
    private static final String COUNT = "select count(*) from git_workspace";
    private static final String COMMENTS =
            """
            select count(*) from pg_attribute where attrelid in
            ('git_session_workspace'::regclass,'git_execution_workspace'::regclass)
            and attnum>0 and not attisdropped and col_description(attrelid,attnum) is null
            """;

    @Test
    void migrationPreservesLegacyAndAllowsManyToManyWorkspaces() throws Exception {
        try (var postgres = new PostgreSQLContainer(DockerImageName.parse(IMAGE))) {
            postgres.start();
            Flyway.configure()
                    .dataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations(MIGRATIONS)
                    .target(OLD_VERSION)
                    .load()
                    .migrate();
            try (var connection =
                            DriverManager.getConnection(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword());
                    var sql = connection.createStatement()) {
                sql.execute(LEGACY);
                sql.execute(CANDIDATE);
                Flyway.configure()
                        .dataSource(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword())
                        .locations(MIGRATIONS)
                        .load()
                        .migrate();
                try (var rows = sql.executeQuery(LEGACY_QUERY)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getBoolean(1)).isTrue();
                    assertThat(rows.getString(2)).isEqualTo("refs/heads/main");
                }
                try (var rows = sql.executeQuery(LEGACY_ASSOCIATION)) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(1);
                }
                sql.execute(WORKSPACES);
                sql.execute(ASSOCIATIONS);
                sql.execute(DETACH);
                try (var rows = sql.executeQuery(COUNT)) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(4);
                }
                try (var rows = sql.executeQuery(OLD_CANDIDATE)) {
                    rows.next();
                    assertThat(rows.getString(1)).isEqualTo("main");
                }
                try (var rows = sql.executeQuery(COMMENTS)) {
                    rows.next();
                    assertThat(rows.getInt(1)).isZero();
                }
            }
        }
    }
}
