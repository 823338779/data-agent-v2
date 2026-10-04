package com.datagent.agentv2.context.hierarchical.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 真实旧版本数据库升级两次后，原摘要和活动指针保持不变。 */
class HierarchicalStepUpgradeIntegrationTest {
    private static final String LOCATION = "classpath:db/agent-context-hierarchical/migration";
    private static final String HISTORY = "flyway_agent_context_hierarchical_schema_history";
    private static final String MAIN_LOCATION = "classpath:db/migration";
    private static final String OLD_DOCUMENT = "{\"schemaVersion\":2,\"preserve\":\"历史原文\"}";

    @Test
    void v2DataSurvivesIncrementalUpgradeAndSecondStartup() throws Exception {
        try (var postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))) {
            postgres.start();
            Flyway.configure()
                    .dataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations(MAIN_LOCATION)
                    .load()
                    .migrate();
            Flyway.configure()
                    .dataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations(LOCATION)
                    .table(HISTORY)
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .target("3")
                    .load()
                    .migrate();
            try (var connection =
                            DriverManager.getConnection(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword());
                    var sql = connection.createStatement()) {
                sql.execute(
                        "insert into tenant(tenant_id,name) values ('tenant_local','Local') on conflict do nothing");
                sql.execute(
                        "insert into conversation_session(tenant_id,conversation_session_id,title) values ('tenant_local','legacy-session','Legacy')");
                try (var insert =
                        connection.prepareStatement(
                                """
                        insert into conversation_context_hierarchical_cold
                        (hierarchical_cold_id,tenant_id,conversation_session_id,start_turn_sequence,end_turn_sequence,
                        start_message_sequence,end_message_sequence,summary_document,persisted_token_count,rendered_token_count,
                        target_rendered_tokens,model_provider,model_name,source_content_sha256,created_at,created_by,updated_at,updated_by)
                        values ('legacy-cold','tenant_local','legacy-session',1,2,1,4,?::jsonb,50,50,100,'fixture','fixture',repeat('0',64),now(),'local',now(),'local')
                        """)) {
                    insert.setString(1, OLD_DOCUMENT);
                    insert.executeUpdate();
                }
                sql.execute(
                        """
                        insert into conversation_context_hierarchical_checkpoint
                        (hierarchical_checkpoint_id,tenant_id,conversation_session_id,active_cold_id,
                        evaluated_up_to_conversation_message_sequence,strategy_generation,created_at,created_by,updated_at,updated_by)
                        values ('legacy-checkpoint','tenant_local','legacy-session','legacy-cold',8,2,now(),'local',now(),'local')
                        """);
                var latest =
                        Flyway.configure()
                                .dataSource(
                                        postgres.getJdbcUrl(),
                                        postgres.getUsername(),
                                        postgres.getPassword())
                                .locations(LOCATION)
                                .table(HISTORY)
                                .load();
                assertThat(latest.migrate().migrationsExecuted).isEqualTo(2);
                assertThat(latest.migrate().migrationsExecuted).isZero();
                try (var rows =
                        sql.executeQuery(
                                "select summary_document->>'preserve',schema_version,step_coverage_json from conversation_context_hierarchical_cold")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("历史原文");
                    assertThat(rows.getInt(2)).isEqualTo(2);
                    assertThat(rows.getString(3)).isNull();
                }
                try (var rows =
                        sql.executeQuery(
                                "select active_cold_id,evaluated_up_to_conversation_message_sequence,strategy_generation,covered_through_json from conversation_context_hierarchical_checkpoint")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("legacy-cold");
                    assertThat(rows.getLong(2)).isEqualTo(8);
                    assertThat(rows.getLong(3)).isEqualTo(2);
                    assertThat(rows.getString(4)).isNull();
                }
            }
        }
    }
}
