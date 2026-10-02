package com.datagent.agentv2.context.hierarchical.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagent.agentv2.context.common.summary.EventSummaryCoverage;
import com.datagent.agentv2.context.hierarchical.configuration.HierarchicalFlywayMigrator;
import com.datagent.agentv2.context.hierarchical.configuration.HierarchicalMapperConfiguration;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCheckpoint;
import com.datagent.agentv2.context.hierarchical.domain.HierarchicalCold;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalCheckpointRepository;
import com.datagent.agentv2.context.hierarchical.persistence.repository.HierarchicalColdRepository;
import com.datagent.agentv2.persistence.AgentPersistenceTestApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 验证迁移后的真实 PostgreSQL 存储边界，包括 JSONB 与旧版本 CAS 拒绝。 */
@SpringBootTest(
        classes = AgentPersistenceTestApplication.class,
        properties = "data-agent.context.strategy=hierarchical")
@Import({HierarchicalMapperConfiguration.class, HierarchicalFlywayMigrator.class})
class HierarchicalStoreIntegrationTest {
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private HierarchicalCheckpointRepository checkpoints;
    @Autowired private HierarchicalColdRepository colds;

    @Test
    void coldIsImmutableAndCheckpointRejectsStaleOrRegressingCoverage() {
        jdbc.update("insert into tenant(tenant_id,name) values ('tenant_local','Local')");
        jdbc.update(
                "insert into conversation_session(tenant_id,conversation_session_id,title) values ('tenant_local','session','Session')");
        var initial = new HierarchicalCheckpoint("checkpoint", "session", null, 0, 0, 0);
        assertThat(checkpoints.create(initial)).isEqualTo(initial);
        var cold =
                new HierarchicalCold(
                        "cold",
                        "session",
                        new EventSummaryCoverage(1, 1, 1, 2),
                        "{}",
                        2,
                        2,
                        10,
                        "provider",
                        "model",
                        "0".repeat(64));
        assertThat(colds.insertOrSelect(cold)).isEqualTo(cold);
        assertThat(colds.insertOrSelect(cold)).isEqualTo(cold);
        var next = new HierarchicalCheckpoint("checkpoint", "session", "cold", 2, 1, 0);
        assertThat(checkpoints.compareAndSetCheckpoint(initial, next)).isTrue();
        assertThat(checkpoints.compareAndSetCheckpoint(initial, next)).isFalse();
        var current = checkpoints.findCheckpoint("session");
        assertThat(current.version()).isEqualTo(1);
        assertThat(current.activeColdId()).isEqualTo("cold");
        var regression = new HierarchicalCheckpoint("checkpoint", "session", "cold", 1, 2, 1);
        assertThat(checkpoints.compareAndSetCheckpoint(current, regression)).isFalse();
    }
}
