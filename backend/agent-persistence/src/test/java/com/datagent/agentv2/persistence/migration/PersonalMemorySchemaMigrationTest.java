package com.datagent.agentv2.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class PersonalMemorySchemaMigrationTest {
    private static final List<String> TABLES =
            List.of(
                    "personal_memory",
                    "personal_memory_state",
                    "personal_memory_operation",
                    "personal_memory_evidence",
                    "personal_memory_suppression",
                    "personal_memory_review_job",
                    "personal_memory_review_lease");

    @Test
    void upgradeFromV6CreatesCommentedPhysicalMemoryTablesAndUniqueKeys() throws Exception {
        try (var postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))) {
            postgres.start();
            var configuration =
                    Flyway.configure()
                            .dataSource(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword())
                            .locations("classpath:db/migration");
            configuration.target("6").load().migrate();
            try (var connection =
                            DriverManager.getConnection(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword());
                    var sql = connection.createStatement()) {
                sql.execute("insert into tenant(tenant_id,name) values ('tenant_local','Local')");
                sql.execute(
                        "insert into user_account(user_id,tenant_id,username,password_hash) values ('user_admin','tenant_local','admin','!')");
                Flyway.configure()
                        .dataSource(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword())
                        .locations("classpath:db/migration")
                        .load()
                        .migrate();

                for (String table : TABLES) {
                    assertThat(
                                    queryInt(
                                            connection,
                                            "select count(*) from pg_class where oid='"
                                                    + table
                                                    + "'::regclass and obj_description(oid) is null"))
                            .as(table + " 表注释")
                            .isZero();
                    assertThat(
                                    queryInt(
                                            connection,
                                            "select count(*) from pg_attribute where attrelid='"
                                                    + table
                                                    + "'::regclass and attnum>0 and not attisdropped and col_description(attrelid,attnum) is null"))
                            .as(table + " 列注释")
                            .isZero();
                }

                sql.execute(
                        "insert into personal_memory(memory_id,tenant_id,user_id,kind,content,normalized_hash,normalization_version,char_count,source,user_protected,version) values ('m1','tenant_local','user_admin','person_profile','原文','hash',1,2,'user_direct',true,1)");
                assertThatThrownBy(
                                () ->
                                        sql.execute(
                                                "insert into personal_memory(memory_id,tenant_id,user_id,kind,content,normalized_hash,normalization_version,char_count,source,user_protected,version) values ('m2','tenant_local','user_admin','person_profile','另一正文','hash',1,4,'user_direct',true,1)"))
                        .isInstanceOf(SQLException.class);
                sql.execute("delete from personal_memory where memory_id='m1'");
                assertThat(
                                queryInt(
                                        connection,
                                        "select count(*) from personal_memory where content='原文'"))
                        .isZero();
            }
        }
    }

    private static int queryInt(java.sql.Connection connection, String query) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(query)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
