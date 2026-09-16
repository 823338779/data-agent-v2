package com.datagent.agentv2.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.time.LocalDateTime;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 验证升级前消息保持无可信时间，新消息才由数据库生成证据时间，避免历史被误判为新授权。 */
class MemoryEvidenceClockMigrationTest {
    @Test
    void upgradePreservesUnknownLegacyTimeAndNewRowsUseDatabaseShanghaiClock() throws Exception {
        try (var postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))) {
            postgres.start();
            Flyway.configure()
                    .dataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .target("8")
                    .load()
                    .migrate();
            try (var connection =
                            DriverManager.getConnection(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword());
                    var sql = connection.createStatement()) {
                sql.execute("set time zone 'UTC'");
                sql.execute("insert into tenant(tenant_id,name) values ('tenant_local','Local')");
                sql.execute(
                        "insert into conversation_session(conversation_session_id,tenant_id,title) values ('conversation','tenant_local','Chat')");
                sql.execute(
                        "insert into conversation_message(conversation_message_id,tenant_id,conversation_session_id,role,content,conversation_message_sequence) values ('legacy','tenant_local','conversation','user','旧消息',1)");
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
                                "select count(*) from information_schema.columns where table_name='conversation_message' and column_name='memory_evidence_created_at'")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(1);
                }
                try (var rows =
                        sql.executeQuery(
                                "select memory_evidence_created_at from conversation_message where conversation_message_id='legacy'")) {
                    rows.next();
                    assertThat(rows.getObject(1)).isNull();
                }
                LocalDateTime before;
                try (var rows =
                        sql.executeQuery("select clock_timestamp() at time zone 'Asia/Shanghai'")) {
                    rows.next();
                    before = rows.getObject(1, LocalDateTime.class);
                }
                sql.execute(
                        "insert into conversation_message(conversation_message_id,tenant_id,conversation_session_id,role,content,conversation_message_sequence,created_at) values ('new','tenant_local','conversation','user','新消息',2,'2000-01-01')");
                try (var rows =
                        sql.executeQuery(
                                "select memory_evidence_created_at,clock_timestamp() at time zone 'Asia/Shanghai' from conversation_message where conversation_message_id='new'")) {
                    rows.next();
                    assertThat(rows.getObject(1, LocalDateTime.class))
                            .isBetween(before, rows.getObject(2, LocalDateTime.class));
                }
            }
        }
    }
}
