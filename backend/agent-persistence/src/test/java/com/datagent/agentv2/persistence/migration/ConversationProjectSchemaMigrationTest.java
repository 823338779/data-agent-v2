package com.datagent.agentv2.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class ConversationProjectSchemaMigrationTest {
    @Test
    void upgradePreservesOldSessionsAndConstrainsProjectReferences() throws Exception {
        try (var postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))) {
            postgres.start();
            Flyway.configure()
                    .dataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .target("4")
                    .load()
                    .migrate();
            try (var connection =
                            DriverManager.getConnection(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword());
                    var sql = connection.createStatement()) {
                sql.execute("insert into tenant(tenant_id, name) values ('tenant_local', 'Local')");
                sql.execute(
                        "insert into conversation_session(conversation_session_id, tenant_id, title) values ('old-session', 'tenant_local', 'old')");
                Flyway.configure()
                        .dataSource(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword())
                        .locations("classpath:db/migration")
                        .load()
                        .migrate();
                try (var rows =
                        sql.executeQuery(
                                "select project_id, pinned_at, title from conversation_session where conversation_session_id='old-session'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("project_id")).isNull();
                    assertThat(rows.getTimestamp("pinned_at")).isNull();
                    assertThat(rows.getString("title")).isEqualTo("old");
                }
                assertThatThrownBy(
                                () ->
                                        sql.execute(
                                                "update conversation_session set project_id='missing' where conversation_session_id='old-session'"))
                        .isInstanceOf(SQLException.class);
                sql.execute(
                        "insert into conversation_project(project_id, tenant_id, name) values ('p1', 'tenant_local', 'Project')");
                sql.execute(
                        "update conversation_session set project_id='p1' where conversation_session_id='old-session'");
                assertThatThrownBy(
                                () ->
                                        sql.execute(
                                                "delete from conversation_project where project_id='p1'"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(
                                () ->
                                        sql.execute(
                                                "update conversation_project set is_deleted=true where project_id='p1'"))
                        .isInstanceOf(SQLException.class);
                try (var rows =
                        sql.executeQuery(
                                "select count(*) from pg_attribute where attrelid='conversation_project'::regclass and attnum>0 and not attisdropped and col_description(attrelid,attnum) is null")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isZero();
                }
            }
        }
    }
}
