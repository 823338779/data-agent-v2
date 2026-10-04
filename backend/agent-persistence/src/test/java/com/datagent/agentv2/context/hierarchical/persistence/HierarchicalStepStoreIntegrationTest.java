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
class HierarchicalStepStoreIntegrationTest {
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
    void coveredAndEvaluatedRemainDistinct() {
        jdbc.update(
                "insert into tenant(tenant_id,name) values ('tenant_local','Local') on conflict do nothing");
        jdbc.update(
                "insert into conversation_session(tenant_id,conversation_session_id,title) values ('tenant_local','step-session','Step')");
        var initial = new HierarchicalCheckpoint("step-checkpoint", "step-session", null, 0, 0, 0);
        checkpoints.create(initial);
        var covered =
                new com.datagent.agentv2.context.hierarchical.domain.ContextStepCursor(
                        2,
                        com.datagent.agentv2.context.hierarchical.domain.ContextStepCursor
                                .PositionKind.INVOCATION_END,
                        1);
        var evaluated =
                new com.datagent.agentv2.context.hierarchical.domain.ContextStepCursor(
                        2,
                        com.datagent.agentv2.context.hierarchical.domain.ContextStepCursor
                                .PositionKind.INVOCATION_END,
                        3);
        var cold =
                new HierarchicalCold(
                        "step-cold",
                        "step-session",
                        new EventSummaryCoverage(1, 1, 1, 2),
                        "{}",
                        2,
                        2,
                        100,
                        "provider",
                        "model",
                        "0".repeat(64),
                        3,
                        new com.datagent.agentv2.context.hierarchical.domain.StepSummaryCoverage(
                                com.datagent.agentv2.context.hierarchical.domain.ContextStepCursor
                                        .ORIGIN,
                                covered,
                                "0".repeat(64)));
        assertThat(colds.insertOrSelect(cold)).isEqualTo(cold);
        var replacement =
                new HierarchicalCheckpoint(
                        "step-checkpoint",
                        "step-session",
                        "step-cold",
                        2,
                        1,
                        0,
                        covered,
                        evaluated,
                        3,
                        3);
        assertThat(checkpoints.compareAndSetCheckpoint(initial, replacement)).isTrue();
        var current = checkpoints.findCheckpoint("step-session");
        assertThat(current.coveredThrough()).isEqualTo(covered);
        assertThat(current.evaluatedThrough()).isEqualTo(evaluated);
        assertThat(current.schemaVersion()).isEqualTo(3);
        // 同一消息的 invocation 倒退仍然是覆盖回退，不能只比较消息序号。
        var regress =
                new HierarchicalCheckpoint(
                        "step-checkpoint",
                        "step-session",
                        "step-cold",
                        2,
                        2,
                        1,
                        com.datagent.agentv2.context.hierarchical.domain.ContextStepCursor.ORIGIN,
                        evaluated,
                        3,
                        3);
        assertThat(checkpoints.compareAndSetCheckpoint(current, regress)).isFalse();
    }
}
